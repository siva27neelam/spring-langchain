package com.loanprocessing.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.decision.DecisionResult;
import com.loanprocessing.decision.DecisionService;
import com.loanprocessing.lifecycle.ApplicationLifecycle;
import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.persistence.JdbcGateway.ApplicationRow;
import com.loanprocessing.support.FaultSwitch;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Service
public class CreditCheckService {
    private final ApplicationLifecycle lifecycle;
    private final RuntimeConfigurationService config;
    private final CreditReportPort port;
    private final DecisionService decisions;
    private final JdbcGateway gateway;
    private final FaultSwitch faults;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public CreditCheckService(
            ApplicationLifecycle lifecycle,
            RuntimeConfigurationService config,
            CreditReportPort port,
            DecisionService decisions,
            JdbcGateway gateway,
            FaultSwitch faults,
            ObjectMapper mapper,
            PlatformTransactionManager manager
    ) {
        this.lifecycle = lifecycle;
        this.config = config;
        this.port = port;
        this.decisions = decisions;
        this.gateway = gateway;
        this.faults = faults;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
    }

    public void onApplicationSubmitted(UUID applicationId) {
        if (faults.poisonCredit()) {
            throw new IllegalStateException("poison credit handler");
        }
        ApplicationRow existing = gateway.find(applicationId).orElse(null);
        if (existing == null) {
            return;
        }
        if (existing.status() != ApplicationStatus.RECEIVED && existing.status() != ApplicationStatus.CREDIT_IN_PROGRESS) {
            return;
        }
        Optional<Claimed> claimed = tx.execute(status -> claim(applicationId));
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        ConfigSnapshot snapshot = config.current();
        CreditReportPort.Pull pull = port.pull(applicationId, claimed.get().providerRequestId(), snapshot);
        tx.executeWithoutResult(status -> complete(claimed.get(), pull, snapshot));
    }

    private Optional<Claimed> claim(UUID applicationId) {
        long lease = config.current().creditLease() == null ? 120 : config.current().creditLease().getSeconds();
        Optional<ApplicationRow> row = lifecycle.claimCredit(applicationId, lease);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        String requestId = gateway.jdbc().query("""
                select provider_request_id from credit_inquiry where application_id = ? and status = 'PENDING' order by created_at limit 1
                """, (rs, n) -> rs.getString(1), applicationId).stream().findFirst().orElse(null);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            gateway.jdbc().update("""
                    insert into credit_inquiry (id, application_id, provider, provider_request_id, status, attempts, created_at, updated_at)
                    values (?, ?, 'STUB', ?, 'PENDING', 1, now(), now())
                    """, UUID.randomUUID(), applicationId, requestId);
        } else {
            gateway.jdbc().update("update credit_inquiry set attempts = attempts + 1, updated_at = now() where provider_request_id = ?", requestId);
        }
        return Optional.of(new Claimed(row.get(), requestId));
    }

    private void complete(Claimed claimed, CreditReportPort.Pull pull, ConfigSnapshot snapshot) {
        DecisionResult result = decisions.credit(pull.score(), snapshot);
        ObjectNode recorded = mapper.createObjectNode();
        recorded.put("provider", pull.provider());
        recorded.put("score", pull.score());
        recorded.put("scoreScale", pull.scoreScale());
        recorded.put("cutoffEnabled", snapshot.cutoffEnabled());
        if (snapshot.cutoffMinimum() == null) {
            recorded.putNull("cutoffMinimum");
        } else {
            recorded.put("cutoffMinimum", snapshot.cutoffMinimum());
        }
        recorded.put("cutoffComparison", snapshot.cutoffComparison() == null ? "" : snapshot.cutoffComparison().name());
        try {
            lifecycle.finishCredit(claimed.row(), result, () -> {
                gateway.jdbc().update("""
                        insert into credit_report (
                            id, application_id, inquiry_id, provider, score, score_scale, cutoff_enabled, cutoff_minimum, cutoff_comparison, pulled_at
                        )
                        select ?, ?, id, ?, ?, ?, ?, ?, ?, now() from credit_inquiry where provider_request_id = ?
                        """, UUID.randomUUID(), claimed.row().id(), pull.provider(), pull.score(), pull.scoreScale(),
                        snapshot.cutoffEnabled(), snapshot.cutoffMinimum(),
                        snapshot.cutoffComparison() == null ? null : snapshot.cutoffComparison().name(), claimed.providerRequestId());
                gateway.jdbc().update("update credit_inquiry set status = 'COMPLETED', updated_at = now() where provider_request_id = ?", claimed.providerRequestId());
                if (result.writeDecisionRow() && result.outcome() != null) {
                    gateway.insertDecision(claimed.row().id(), null, result, "system", null);
                }
            }, recorded);
        } catch (DuplicateKeyException ignored) {
            // A completed credit report already exists. The status predicate or unique key stops a second decision.
        }
    }

    private record Claimed(ApplicationRow row, String providerRequestId) {
        boolean isEmpty() {
            return false;
        }
    }
}
