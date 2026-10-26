package com.loanprocessing.lifecycle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.decision.DecisionResult;
import com.loanprocessing.model.ActorType;
import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.model.ReasonCode;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.persistence.JdbcGateway.ApplicationRow;
import com.loanprocessing.security.RequestContext;
import com.loanprocessing.support.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class ApplicationLifecycle {
    private final JdbcGateway gateway;

    public ApplicationLifecycle(JdbcGateway gateway) {
        this.gateway = gateway;
    }

    @Transactional
    public void recordCreation(ApplicationRow row) {
        gateway.history(row.id(), null, ApplicationStatus.RECEIVED.name(), null, ActorType.CUSTOMER, row.customerPrincipal(), row.correlationId());
        ObjectNode metadata = gatewayJson();
        metadata.put("ssn_captured", true);
        metadata.put("dob_captured", true);
        gateway.audit("APPLICATION_CREATED", "application", row.id().toString(), "SUCCESS", null, metadata);
        gateway.audit("STATUS_CHANGED", "application", row.id().toString(), "SUCCESS", null, statusMeta(null, ApplicationStatus.RECEIVED, null));
        gateway.outbox(row.id(), "ApplicationSubmitted", row.correlationId());
    }

    @Transactional
    public Optional<ApplicationRow> claimCredit(UUID id, long leaseSeconds) {
        JdbcTemplate jdbc = gateway.jdbc();
        int updated = jdbc.update("""
                update loan_application
                set status = 'CREDIT_IN_PROGRESS',
                    version = version + 1,
                    lease_until = now() + (? * interval '1 second'),
                    lease_generation = lease_generation + 1,
                    attempt_credit = attempt_credit + 1,
                    updated_at = now()
                where id = ?
                  and attempt_credit < credit_attempt_budget
                  and (status = 'RECEIVED' or (status = 'CREDIT_IN_PROGRESS' and lease_until < now()))
                """, leaseSeconds, id);
        if (updated != 1) {
            exhaustCredit(id);
            return Optional.empty();
        }
        ApplicationRow row = gateway.find(id).orElseThrow();
        String from = row.attemptCredit() == 1 ? ApplicationStatus.RECEIVED.name() : ApplicationStatus.CREDIT_IN_PROGRESS.name();
        gateway.history(id, from, ApplicationStatus.CREDIT_IN_PROGRESS.name(), null, ActorType.SYSTEM, "credit-worker", row.correlationId());
        gateway.audit("CREDIT_REQUESTED", "application", id.toString(), "SUCCESS", null, gatewayJson());
        if (row.attemptCredit() == 1) {
            gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", null,
                    statusMeta(ApplicationStatus.RECEIVED, ApplicationStatus.CREDIT_IN_PROGRESS, null));
        }
        return Optional.of(row);
    }

    private void exhaustCredit(UUID id) {
        JdbcTemplate jdbc = gateway.jdbc();
        int updated = jdbc.update("""
                update loan_application
                set status = 'CREDIT_FAILED', status_reason = 'CREDIT_RETRIES_EXHAUSTED', version = version + 1, updated_at = now(), lease_until = null
                where id = ?
                  and attempt_credit >= credit_attempt_budget
                  and (status = 'RECEIVED' or (status = 'CREDIT_IN_PROGRESS' and lease_until < now()))
                """, id);
        if (updated == 1) {
            ApplicationRow row = gateway.find(id).orElseThrow();
            gateway.history(id, ApplicationStatus.CREDIT_IN_PROGRESS.name(), ApplicationStatus.CREDIT_FAILED.name(),
                    ReasonCode.CREDIT_RETRIES_EXHAUSTED.name(), ActorType.SYSTEM, "credit-worker", row.correlationId());
            gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", ReasonCode.CREDIT_RETRIES_EXHAUSTED.name(),
                    statusMeta(ApplicationStatus.CREDIT_IN_PROGRESS, ApplicationStatus.CREDIT_FAILED, ReasonCode.CREDIT_RETRIES_EXHAUSTED.name()));
        }
    }

    @Transactional
    public boolean finishCredit(ApplicationRow claimed, DecisionResult result, Runnable writes, com.fasterxml.jackson.databind.JsonNode recorded) {
        ApplicationStatus to = result.status();
        if (!TransitionTable.allowed(ApplicationStatus.CREDIT_IN_PROGRESS, to)) {
            throw new ApiException(409, "ILLEGAL_TRANSITION", "credit transition is not legal");
        }
        if (to == ApplicationStatus.APPROVED) {
            throw new ApiException(409, "ILLEGAL_TRANSITION", "credit cannot approve");
        }
        boolean ok = gateway.transition(claimed.id(), ApplicationStatus.CREDIT_IN_PROGRESS, claimed.version(), to,
                result.reason() == null ? null : result.reason().name(), ", lease_until = null");
        if (!ok) {
            return false;
        }
        writes.run();
        ApplicationRow row = gateway.find(claimed.id()).orElseThrow();
        gateway.history(claimed.id(), ApplicationStatus.CREDIT_IN_PROGRESS.name(), to.name(),
                result.reason() == null ? null : result.reason().name(), ActorType.SYSTEM, "credit-worker", row.correlationId());
        if (result.writeDecisionRow()) {
            gateway.audit("DECISION_RECORDED", "application", claimed.id().toString(), "SUCCESS", result.ruleCode(), decisionMeta(result));
        } else {
            ObjectNode creditMeta = recorded instanceof ObjectNode node ? node : gatewayJson();
            gateway.audit("CREDIT_RECORDED", "application", claimed.id().toString(), "SUCCESS",
                    result.reason() == null ? null : result.reason().name(), creditMeta);
        }
        gateway.audit("STATUS_CHANGED", "application", claimed.id().toString(), "SUCCESS",
                result.reason() == null ? null : result.reason().name(),
                statusMeta(ApplicationStatus.CREDIT_IN_PROGRESS, to, result.reason() == null ? null : result.reason().name()));
        return true;
    }

    @Transactional
    public ApplicationStatus acceptPayslip(UUID id, long version, int acceptedAfter, int required) {
        ApplicationRow row = gateway.lock(id);
        if (row.version() != version || row.status() != ApplicationStatus.PAYSLIP_REVIEW) {
            throw new ApiException(409, "STATUS_NOT_ACCEPTING_DOCUMENTS", "application is not accepting documents");
        }
        ApplicationStatus to = acceptedAfter >= required ? ApplicationStatus.EXTRACTION_QUEUED : ApplicationStatus.PAYSLIP_REVIEW;
        if (!TransitionTable.allowed(ApplicationStatus.PAYSLIP_REVIEW, to)) {
            throw new ApiException(409, "ILLEGAL_TRANSITION", "document transition is not legal");
        }
        String reason = to == ApplicationStatus.EXTRACTION_QUEUED ? null : row.statusReason();
        int updated = gateway.jdbc().update("""
                update loan_application
                set status = ?, status_reason = ?, version = version + 1, updated_at = now(), last_activity_at = now()
                where id = ? and status = 'PAYSLIP_REVIEW' and version = ?
                """, to.name(), reason, id, version);
        if (updated != 1) {
            throw new ApiException(409, "STATUS_NOT_ACCEPTING_DOCUMENTS", "application is not accepting documents");
        }
        if (to == ApplicationStatus.EXTRACTION_QUEUED) {
            gateway.history(id, ApplicationStatus.PAYSLIP_REVIEW.name(), to.name(), null, ActorType.CUSTOMER, RequestContext.get().actorId(), row.correlationId());
            gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", null,
                    statusMeta(ApplicationStatus.PAYSLIP_REVIEW, to, null));
            gateway.outbox(id, "ExtractionRequested", row.correlationId());
        }
        return to;
    }

    @Transactional
    public Optional<ExtractionClaim> claimExtraction(UUID id, long leaseSeconds, boolean incrementAttempt) {
        String attemptSql = incrementAttempt ? "attempt_extraction = attempt_extraction + 1," : "";
        String guard = incrementAttempt ? "and attempt_extraction < extraction_attempt_budget" : "";
        var rows = gateway.jdbc().query("""
                update loan_application
                set status = 'EXTRACTING',
                    version = version + 1,
                    %s
                    lease_until = now() + (? * interval '1 second'),
                    lease_generation = lease_generation + 1,
                    updated_at = now()
                where id = ?
                  %s
                  and (status = 'EXTRACTION_QUEUED' or (status = 'EXTRACTING' and lease_until < now()))
                returning id, version, lease_generation, attempt_extraction, correlation_id, status
                """.formatted(attemptSql, guard), (rs, n) -> new ExtractionClaim(
                rs.getObject("id", UUID.class),
                rs.getLong("version"),
                rs.getLong("lease_generation"),
                rs.getInt("attempt_extraction"),
                rs.getObject("correlation_id", UUID.class)
        ), leaseSeconds, id);
        if (rows.isEmpty()) {
            exhaustExtraction(id);
            return Optional.empty();
        }
        ExtractionClaim claim = rows.get(0);
        gateway.history(id, ApplicationStatus.EXTRACTION_QUEUED.name(), ApplicationStatus.EXTRACTING.name(), null,
                ActorType.SYSTEM, "extraction-worker", claim.correlationId());
        gateway.audit("EXTRACTION_STARTED", "application", id.toString(), "SUCCESS", null, gatewayJson());
        gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", null,
                statusMeta(ApplicationStatus.EXTRACTION_QUEUED, ApplicationStatus.EXTRACTING, null));
        return Optional.of(claim);
    }

    private void exhaustExtraction(UUID id) {
        int updated = gateway.jdbc().update("""
                update loan_application
                set status = 'EXTRACTION_FAILED', status_reason = 'EXTRACTION_RETRIES_EXHAUSTED',
                    version = version + 1, updated_at = now(), lease_until = null
                where id = ?
                  and attempt_extraction >= extraction_attempt_budget
                  and (status = 'EXTRACTION_QUEUED' or (status = 'EXTRACTING' and lease_until < now()))
                """, id);
        if (updated == 1) {
            ApplicationRow row = gateway.find(id).orElseThrow();
            gateway.history(id, row.status().name(), ApplicationStatus.EXTRACTION_FAILED.name(),
                    ReasonCode.EXTRACTION_RETRIES_EXHAUSTED.name(), ActorType.SYSTEM, "extraction-worker", row.correlationId());
            gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", ReasonCode.EXTRACTION_RETRIES_EXHAUSTED.name(),
                    statusMeta(ApplicationStatus.EXTRACTING, ApplicationStatus.EXTRACTION_FAILED, ReasonCode.EXTRACTION_RETRIES_EXHAUSTED.name()));
        }
    }

    @Transactional
    public boolean applyExtraction(UUID id, long version, long leaseGeneration, DecisionResult result, Runnable fieldWrites, Runnable decisionWrites) {
        ApplicationRow locked = gateway.lock(id);
        if (locked.status() != ApplicationStatus.EXTRACTING || locked.version() != version || locked.leaseGeneration() != leaseGeneration) {
            return false;
        }
        if (locked.withdrawRequested() && result.status() != ApplicationStatus.WITHDRAWN) {
            gateway.jdbc().update("""
                    update loan_application
                    set status = 'WITHDRAWN', status_reason = 'CUSTOMER_WITHDREW', version = version + 1,
                        updated_at = now(), lease_until = null, withdraw_requested = false
                    where id = ? and status = 'EXTRACTING' and version = ? and lease_generation = ?
                    """, id, version, leaseGeneration);
            fieldWrites.run();
            gateway.history(id, ApplicationStatus.EXTRACTING.name(), ApplicationStatus.WITHDRAWN.name(),
                    ReasonCode.CUSTOMER_WITHDREW.name(), ActorType.SYSTEM, "extraction-worker", locked.correlationId());
            gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", ReasonCode.CUSTOMER_WITHDREW.name(),
                    statusMeta(ApplicationStatus.EXTRACTING, ApplicationStatus.WITHDRAWN, ReasonCode.CUSTOMER_WITHDREW.name()));
            return true;
        }
        ApplicationStatus to = result.status();
        if (to == ApplicationStatus.APPROVED && !result.approves()) {
            throw new ApiException(409, "ILLEGAL_TRANSITION", "approval was not authorized");
        }
        if (!TransitionTable.allowed(ApplicationStatus.EXTRACTING, to)) {
            throw new ApiException(409, "ILLEGAL_TRANSITION", "extraction transition is not legal");
        }
        boolean ok = gateway.jdbc().update("""
                update loan_application
                set status = ?, status_reason = ?, version = version + 1, updated_at = now(),
                    lease_until = null, withdraw_requested = false
                where id = ? and status = 'EXTRACTING' and version = ? and lease_generation = ?
                """, to.name(), result.reason() == null ? null : result.reason().name(), id, version, leaseGeneration) == 1;
        if (!ok) {
            return false;
        }
        fieldWrites.run();
        if (result.writeDecisionRow()) {
            decisionWrites.run();
        }
        ApplicationRow row = gateway.find(id).orElseThrow();
        gateway.history(id, ApplicationStatus.EXTRACTING.name(), to.name(), result.reason() == null ? null : result.reason().name(),
                ActorType.SYSTEM, "extraction-worker", row.correlationId());
        if (result.writeDecisionRow()) {
            gateway.audit("DECISION_RECORDED", "decision", id.toString(), "SUCCESS", result.ruleCode(), decisionMeta(result));
        }
        gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS",
                result.reason() == null ? null : result.reason().name(),
                statusMeta(ApplicationStatus.EXTRACTING, to, result.reason() == null ? null : result.reason().name()));
        return true;
    }

    @Transactional
    public Withdrawal withdraw(UUID id, String principal, boolean operator) {
        ApplicationRow row = gateway.lock(id);
        if (!operator && !row.customerPrincipal().equals(principal)) {
            throw new ApiException(404, "NOT_FOUND", "application not found");
        }
        if (row.status().terminal()) {
            throw new ApiException(409, "TERMINAL_STATUS", "application is already terminal");
        }
        if (row.status() == ApplicationStatus.EXTRACTING) {
            gateway.jdbc().update("""
                    update loan_application set withdraw_requested = true, last_activity_at = now(), updated_at = now()
                    where id = ? and status = 'EXTRACTING'
                    """, id);
            gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", ReasonCode.CUSTOMER_WITHDREW.name(), gatewayJson().put("withdrawRequested", true));
            return new Withdrawal(ApplicationStatus.EXTRACTING, true);
        }
        if (!TransitionTable.allowed(row.status(), ApplicationStatus.WITHDRAWN)) {
            throw new ApiException(409, "ILLEGAL_TRANSITION", "withdrawal is not legal from this status");
        }
        boolean ok = gateway.jdbc().update("""
                update loan_application
                set status = 'WITHDRAWN', status_reason = 'CUSTOMER_WITHDREW', version = version + 1,
                    updated_at = now(), last_activity_at = now(), lease_until = null
                where id = ? and status = ? and version = ?
                """, id, row.status().name(), row.version()) == 1;
        if (!ok) {
            throw new ApiException(409, "STATUS_CONFLICT", "application changed; reload");
        }
        gateway.history(id, row.status().name(), ApplicationStatus.WITHDRAWN.name(), ReasonCode.CUSTOMER_WITHDREW.name(),
                ActorType.CUSTOMER, principal, row.correlationId());
        gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", ReasonCode.CUSTOMER_WITHDREW.name(),
                statusMeta(row.status(), ApplicationStatus.WITHDRAWN, ReasonCode.CUSTOMER_WITHDREW.name()));
        return new Withdrawal(ApplicationStatus.WITHDRAWN, false);
    }

    @Transactional
    public int abandonExpired(long olderThanSeconds) {
        var ids = gateway.jdbc().query("""
                select id from loan_application
                where status = 'PAYSLIP_REVIEW'
                  and last_activity_at < now() - (? * interval '1 second')
                for update skip locked
                """, (rs, n) -> rs.getObject("id", UUID.class), olderThanSeconds);
        int count = 0;
        for (UUID id : ids) {
            ApplicationRow row = gateway.find(id).orElseThrow();
            if (row.status() != ApplicationStatus.PAYSLIP_REVIEW) {
                continue;
            }
            int updated = gateway.jdbc().update("""
                    update loan_application
                    set status = 'ABANDONED', status_reason = 'PAYSLIP_TIMEOUT', version = version + 1, updated_at = now()
                    where id = ? and status = 'PAYSLIP_REVIEW' and version = ?
                    """, id, row.version());
            if (updated == 1) {
                gateway.history(id, ApplicationStatus.PAYSLIP_REVIEW.name(), ApplicationStatus.ABANDONED.name(),
                        ReasonCode.PAYSLIP_TIMEOUT.name(), ActorType.SYSTEM, "abandon-job", row.correlationId());
                gateway.audit("APPLICATION_ABANDONED", "application", id.toString(), "SUCCESS", ReasonCode.PAYSLIP_TIMEOUT.name(), gatewayJson());
                gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", ReasonCode.PAYSLIP_TIMEOUT.name(),
                        statusMeta(ApplicationStatus.PAYSLIP_REVIEW, ApplicationStatus.ABANDONED, ReasonCode.PAYSLIP_TIMEOUT.name()));
                count++;
            }
        }
        return count;
    }

    @Transactional
    public void operatorResolve(UUID id, DecisionResult result, Runnable writes) {
        if (!result.approves() && result.status() != ApplicationStatus.DECLINED) {
            throw new ApiException(422, "OPERATOR_DECISION", "outcome must approve or decline");
        }
        ApplicationRow row = gateway.lock(id);
        if (row.status() != ApplicationStatus.MANUAL_HOLD) {
            throw new ApiException(409, "ILLEGAL_TRANSITION", "operator decisions start from manual hold");
        }
        if (!TransitionTable.allowed(row.status(), result.status())) {
            throw new ApiException(409, "ILLEGAL_TRANSITION", "operator transition is not legal");
        }
        boolean ok = gateway.transition(row.id(), row.status(), row.version(), result.status(), ReasonCode.OPERATOR_DECISION.name(), "");
        if (!ok) {
            throw new ApiException(409, "STATUS_CONFLICT", "application changed; reload");
        }
        writes.run();
        gateway.history(id, ApplicationStatus.MANUAL_HOLD.name(), result.status().name(), ReasonCode.OPERATOR_DECISION.name(),
                ActorType.OPERATOR, RequestContext.get().actorId(), row.correlationId());
        gateway.audit("DECISION_RECORDED", "decision", id.toString(), "SUCCESS", ReasonCode.OPERATOR_DECISION.name(), decisionMeta(result));
        gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", ReasonCode.OPERATOR_DECISION.name(),
                statusMeta(ApplicationStatus.MANUAL_HOLD, result.status(), ReasonCode.OPERATOR_DECISION.name()));
    }

    @Transactional
    public void scheduleRetry(UUID id, String step, int addedBudget) {
        ApplicationRow row = gateway.lock(id);
        if ("CREDIT".equals(step)) {
            if (row.status() != ApplicationStatus.CREDIT_FAILED) {
                throw new ApiException(409, "ILLEGAL_TRANSITION", "credit retry starts from credit failure");
            }
            gateway.jdbc().update("""
                    update loan_application
                    set status = 'CREDIT_IN_PROGRESS', status_reason = null, version = version + 1,
                        credit_attempt_budget = attempt_credit + ?, lease_until = now() - interval '1 second', updated_at = now()
                    where id = ? and status = 'CREDIT_FAILED'
                    """, addedBudget, id);
            gateway.history(id, ApplicationStatus.CREDIT_FAILED.name(), ApplicationStatus.CREDIT_IN_PROGRESS.name(), null,
                    ActorType.OPERATOR, RequestContext.get().actorId(), row.correlationId());
            gateway.outbox(id, "ApplicationSubmitted", row.correlationId());
        } else if ("EXTRACTION".equals(step)) {
            if (row.status() != ApplicationStatus.EXTRACTION_FAILED) {
                throw new ApiException(409, "ILLEGAL_TRANSITION", "extraction retry starts from extraction failure");
            }
            gateway.jdbc().update("""
                    update loan_application
                    set status = 'EXTRACTION_QUEUED', status_reason = null, version = version + 1,
                        extraction_attempt_budget = attempt_extraction + ?, lease_until = null, updated_at = now()
                    where id = ? and status = 'EXTRACTION_FAILED'
                    """, addedBudget, id);
            gateway.history(id, ApplicationStatus.EXTRACTION_FAILED.name(), ApplicationStatus.EXTRACTION_QUEUED.name(), null,
                    ActorType.OPERATOR, RequestContext.get().actorId(), row.correlationId());
            gateway.outbox(id, "ExtractionRequested", row.correlationId());
        } else {
            throw new ApiException(422, "VALIDATION", "step must be CREDIT or EXTRACTION");
        }
        gateway.audit("RETRY_SCHEDULED", "application", id.toString(), "SUCCESS", step, gatewayJson().put("step", step));
        gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", "RETRY_SCHEDULED", gatewayJson().put("step", step));
    }

    @Transactional
    public void moveToHold(UUID id, DecisionResult result) {
        ApplicationRow row = gateway.lock(id);
        if (row.status() == result.status()) {
            return;
        }
        if (!TransitionTable.allowed(row.status(), result.status())) {
            return;
        }
        boolean ok = gateway.transition(row.id(), row.status(), row.version(), result.status(),
                result.reason() == null ? null : result.reason().name(), ", lease_until = null");
        if (!ok) {
            return;
        }
        if (result.writeDecisionRow() && result.outcome() != null) {
            gateway.jdbc().update("""
                    insert into decision (id, application_id, extraction_run_id, source, outcome, rule_code, secondary_rule_codes,
                                          salary_compared, threshold, comparison, config_generation, actor_id, created_at)
                    values (?, ?, null, ?, ?, ?, null, ?, ?, ?, ?, 'system', now())
                    """, UUID.randomUUID(), id, result.source(), result.outcome().name(), result.ruleCode(),
                    result.salaryCompared(), result.threshold(), result.comparison(), result.configGeneration());
        }
        gateway.history(id, row.status().name(), result.status().name(), result.reason().name(),
                ActorType.SYSTEM, "worker", row.correlationId());
        gateway.audit("STATUS_CHANGED", "application", id.toString(), "SUCCESS", result.reason().name(),
                statusMeta(row.status(), result.status(), result.reason().name()));
        gateway.audit("DECISION_RECORDED", "application", id.toString(), "SUCCESS", result.ruleCode(), decisionMeta(result));
    }

    public JdbcGateway gateway() {
        return gateway;
    }

    private ObjectNode gatewayJson() {
        return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    }

    private ObjectNode statusMeta(ApplicationStatus from, ApplicationStatus to, String reason) {
        ObjectNode node = gatewayJson();
        node.put("from", from == null ? "" : from.name());
        node.put("to", to.name());
        if (reason != null) {
            node.put("reason", reason);
        }
        return node;
    }

    private ObjectNode decisionMeta(DecisionResult result) {
        ObjectNode node = gatewayJson();
        node.put("outcome", result.outcome() == null ? "" : result.outcome().name());
        node.put("rule", result.ruleCode() == null ? "" : result.ruleCode());
        if (result.threshold() != null) {
            node.put("threshold", result.threshold().toPlainString());
        }
        if (result.comparison() != null) {
            node.put("comparison", result.comparison());
        }
        node.put("configGeneration", result.configGeneration());
        if (result.salaryCompared() != null) {
            node.put("salary", result.salaryCompared().toPlainString());
        }
        return node;
    }

    public record ExtractionClaim(UUID id, long version, long leaseGeneration, int attemptExtraction, UUID correlationId) {
    }

    public record Withdrawal(ApplicationStatus status, boolean withdrawRequested) {
    }
}
