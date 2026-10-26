package com.loanprocessing.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.decision.DecisionResult;
import com.loanprocessing.model.ActorType;
import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.security.RequestContext;
import com.loanprocessing.support.ApiException;
import com.loanprocessing.support.FaultSwitch;
import com.loanprocessing.support.Hashes;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcGateway {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final FaultSwitch faults;

    public JdbcGateway(JdbcTemplate jdbc, ObjectMapper mapper, FaultSwitch faults) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.faults = faults;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public Optional<ApplicationRow> find(UUID id) {
        List<ApplicationRow> rows = jdbc.query(ApplicationRow.SELECT + " where id = ?", ApplicationRow.MAPPER, id);
        return rows.stream().findFirst();
    }

    public ApplicationRow lock(UUID id) {
        List<ApplicationRow> rows = jdbc.query(ApplicationRow.SELECT + " where id = ? for update", ApplicationRow.MAPPER, id);
        return rows.stream().findFirst().orElseThrow(() -> new ApiException(404, "NOT_FOUND", "application not found"));
    }

    public void insertApplication(ApplicationRow row) {
        jdbc.update("""
                insert into loan_application (
                    id, version, status, status_reason, customer_principal, wrapped_dek, encryption_key_version,
                    first_name_enc, last_name_enc, ssn_enc, ssn_last4, ssn_hmac, dob_enc, email_enc, phone_enc, address_enc,
                    correlation_id, lease_generation, attempt_credit, attempt_extraction, credit_attempt_budget,
                    extraction_attempt_budget, withdraw_requested, last_activity_at, created_at, updated_at
                ) values (
                    ?, 0, 'RECEIVED', null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, ?, ?, false, now(), now(), now()
                )
                """, row.id(), row.customerPrincipal(), row.wrappedDek(), row.encryptionKeyVersion(), row.firstNameEnc(),
                row.lastNameEnc(), row.ssnEnc(), row.ssnLast4(), row.ssnHmac(), row.dobEnc(), row.emailEnc(),
                row.phoneEnc(), row.addressEnc(), row.correlationId(), row.creditAttemptBudget(), row.extractionAttemptBudget());
    }

    public boolean transition(UUID id, ApplicationStatus from, long version, ApplicationStatus to, String reason, String extra, Object... params) {
        Object[] all = new Object[params.length + 5];
        all[0] = to.name();
        all[1] = reason;
        System.arraycopy(params, 0, all, 2, params.length);
        all[all.length - 3] = id;
        all[all.length - 2] = from.name();
        all[all.length - 1] = version;
        String sql = """
                update loan_application
                set status = ?, status_reason = ?, version = version + 1, updated_at = now()
                """ + extra + """
                 where id = ? and status = ? and version = ?
                """;
        return jdbc.update(sql, all) == 1;
    }

    public void history(UUID id, String from, String to, String reason, ActorType actor, String actorId, UUID correlation) {
        jdbc.update("""
                insert into application_status_history
                (id, application_id, from_status, to_status, reason_code, actor_type, actor_id, correlation_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), id, from, to, reason, actor.name(), actorId, correlation);
    }

    public void audit(String action, String resourceType, String resourceId, String outcome, String reason, ObjectNode metadata) {
        if (faults.auditFail()) {
            throw new ApiException(503, "AUDIT_FAILED", "audit write failed");
        }
        RequestContext.Ids ids = RequestContext.get();
        UUID eventId = UUID.randomUUID();
        byte[] prev = previousHash();
        ObjectNode body = mapper.createObjectNode();
        body.put("eventId", eventId.toString());
        body.put("action", action);
        body.put("resourceType", resourceType);
        body.put("resourceId", resourceId);
        body.put("outcome", outcome);
        body.put("reason", reason == null ? "" : reason);
        body.put("actorId", ids.actorId());
        body.set("metadata", metadata == null ? mapper.createObjectNode() : metadata);
        byte[] hash = Hashes.sha256(concat(prev, body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("""
                insert into audit_event (
                    event_id, occurred_at, correlation_id, request_id, causation_id, trace_id, actor_type, actor_id,
                    action, resource_type, resource_id, outcome, reason_code, metadata, prev_hash, event_hash
                ) values (?, now(), ?, ?, null, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, ids.correlationId(), ids.requestId(), ids.actorType().name(), ids.actorId(), action,
                resourceType, resourceId, outcome, reason, jsonb(metadata == null ? "{}" : metadata.toString()), prev, hash);
    }

    private byte[] previousHash() {
        List<byte[]> rows = jdbc.query("select event_hash from audit_event order by occurred_at desc, event_id desc limit 1 for update",
                (rs, n) -> rs.getBytes(1));
        if (rows.isEmpty()) {
            return new byte[32];
        }
        return rows.get(0);
    }

    public void outbox(UUID aggregateId, String eventType, UUID correlationId) {
        ObjectNode payload = mapper.createObjectNode();
        if (aggregateId != null) {
            payload.put("applicationId", aggregateId.toString());
        }
        if (correlationId != null) {
            payload.put("correlationId", correlationId.toString());
        }
        jdbc.update("""
                insert into outbox (id, aggregate_id, event_type, payload, correlation_id, created_at, attempts)
                values (?, ?, ?, ?, ?, now(), 0)
                """, UUID.randomUUID(), aggregateId, eventType, jsonb(payload.toString()), correlationId);
    }

    public List<OutboxRow> claimOutbox(int limit) {
        return jdbc.query("""
                select id, aggregate_id, event_type, payload::text, correlation_id, attempts
                from outbox
                where published_at is null and (next_attempt_at is null or next_attempt_at <= now())
                order by created_at
                for update skip locked
                limit ?
                """, (rs, n) -> new OutboxRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getObject(5, UUID.class), rs.getInt(6)), limit);
    }

    public void publish(UUID id) {
        jdbc.update("update outbox set published_at = now() where id = ?", id);
    }

    public void defer(UUID id, int attempts, String error, int delaySeconds) {
        jdbc.update("""
                update outbox set attempts = ?, last_error = ?, next_attempt_at = now() + (? * interval '1 second')
                where id = ?
                """, attempts, trim(error), delaySeconds, id);
    }

    public void deadLetter(OutboxRow row, String error) {
        jdbc.update("""
                insert into outbox_dead_letter (id, aggregate_id, event_type, payload, correlation_id, created_at, attempts, last_error, dead_lettered_at)
                values (?, ?, ?, ?, ?, now(), ?, ?, now())
                """, row.id(), row.aggregateId(), row.eventType(), jsonb(row.payload()), row.correlationId(), row.attempts(), trim(error));
        publish(row.id());
    }

    public void insertDecision(UUID applicationId, UUID extractionRunId, DecisionResult result, String actor, byte[] comment) {
        jdbc.update("""
                insert into decision (id, application_id, extraction_run_id, source, outcome, rule_code, secondary_rule_codes,
                                      salary_compared, threshold, comparison, config_generation, actor_id, comment_enc, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, ps -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, applicationId);
            if (extractionRunId == null) {
                ps.setNull(3, Types.OTHER);
            } else {
                ps.setObject(3, extractionRunId);
            }
            ps.setString(4, result.source());
            ps.setString(5, result.outcome().name());
            ps.setString(6, result.ruleCode());
            if (result.secondaryRuleCodes().isEmpty()) {
                ps.setNull(7, Types.ARRAY);
            } else {
                ps.setArray(7, ps.getConnection().createArrayOf("text", result.secondaryRuleCodes().toArray(String[]::new)));
            }
            setDecimal(ps, 8, result.salaryCompared());
            setDecimal(ps, 9, result.threshold());
            ps.setString(10, result.comparison());
            ps.setLong(11, result.configGeneration());
            ps.setString(12, actor);
            if (comment == null) {
                ps.setNull(13, Types.BINARY);
            } else {
                ps.setBytes(13, comment);
            }
        });
    }

    private static void setDecimal(java.sql.PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    public PGobject jsonb(String json) {
        try {
            PGobject object = new PGobject();
            object.setType("jsonb");
            object.setValue(json);
            return object;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Array textArray(java.sql.Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray(String[]::new));
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }

    private static String trim(String error) {
        if (error == null) {
            return null;
        }
        String cleaned = error.replaceAll("\\d{3}-\\d{2}-\\d{4}", "[redacted-ssn]");
        return cleaned.length() > 400 ? cleaned.substring(0, 400) : cleaned;
    }

    public record OutboxRow(UUID id, UUID aggregateId, String eventType, String payload, UUID correlationId, int attempts) {
    }

    public record ApplicationRow(
            UUID id,
            long version,
            ApplicationStatus status,
            String statusReason,
            String customerPrincipal,
            byte[] wrappedDek,
            int encryptionKeyVersion,
            byte[] firstNameEnc,
            byte[] lastNameEnc,
            byte[] ssnEnc,
            String ssnLast4,
            byte[] ssnHmac,
            byte[] dobEnc,
            byte[] emailEnc,
            byte[] phoneEnc,
            byte[] addressEnc,
            UUID correlationId,
            OffsetDateTime leaseUntil,
            long leaseGeneration,
            int attemptCredit,
            int attemptExtraction,
            int creditAttemptBudget,
            int extractionAttemptBudget,
            boolean withdrawRequested,
            OffsetDateTime lastActivityAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        static final String SELECT = """
                select id, version, status, status_reason, customer_principal, wrapped_dek, encryption_key_version,
                       first_name_enc, last_name_enc, ssn_enc, ssn_last4, ssn_hmac, dob_enc, email_enc, phone_enc, address_enc,
                       correlation_id, lease_until, lease_generation, attempt_credit, attempt_extraction,
                       credit_attempt_budget, extraction_attempt_budget, withdraw_requested, last_activity_at, created_at, updated_at
                from loan_application
                """;

        static final RowMapper<ApplicationRow> MAPPER = (ResultSet rs, int n) -> new ApplicationRow(
                rs.getObject("id", UUID.class),
                rs.getLong("version"),
                ApplicationStatus.valueOf(rs.getString("status")),
                rs.getString("status_reason"),
                rs.getString("customer_principal"),
                rs.getBytes("wrapped_dek"),
                rs.getInt("encryption_key_version"),
                rs.getBytes("first_name_enc"),
                rs.getBytes("last_name_enc"),
                rs.getBytes("ssn_enc"),
                rs.getString("ssn_last4"),
                rs.getBytes("ssn_hmac"),
                rs.getBytes("dob_enc"),
                rs.getBytes("email_enc"),
                rs.getBytes("phone_enc"),
                rs.getBytes("address_enc"),
                rs.getObject("correlation_id", UUID.class),
                time(rs, "lease_until"),
                rs.getLong("lease_generation"),
                rs.getInt("attempt_credit"),
                rs.getInt("attempt_extraction"),
                rs.getInt("credit_attempt_budget"),
                rs.getInt("extraction_attempt_budget"),
                rs.getBoolean("withdraw_requested"),
                time(rs, "last_activity_at"),
                time(rs, "created_at"),
                time(rs, "updated_at")
        );

        private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
            Timestamp timestamp = rs.getTimestamp(column);
            if (timestamp == null) {
                return null;
            }
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
    }
}
