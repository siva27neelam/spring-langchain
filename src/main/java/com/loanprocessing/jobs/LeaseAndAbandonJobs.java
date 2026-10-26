package com.loanprocessing.jobs;

import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.document.DocumentStoragePort;
import com.loanprocessing.lifecycle.ApplicationLifecycle;
import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.outbox.OutboxRelay;
import com.loanprocessing.persistence.JdbcGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.HashSet;
import java.util.UUID;

@Component
public class LeaseAndAbandonJobs {
    private static final Logger log = LoggerFactory.getLogger(LeaseAndAbandonJobs.class);
    private final OutboxRelay outbox;
    private final ApplicationLifecycle lifecycle;
    private final RuntimeConfigurationService config;
    private final JdbcGateway gateway;
    private final DocumentStoragePort storage;
    private final String owner = UUID.randomUUID().toString();
    private final boolean enabled;

    public LeaseAndAbandonJobs(
            OutboxRelay outbox,
            ApplicationLifecycle lifecycle,
            RuntimeConfigurationService config,
            JdbcGateway gateway,
            DocumentStoragePort storage,
            @Value("${loan.jobs.enabled:true}") boolean enabled
    ) {
        this.outbox = outbox;
        this.lifecycle = lifecycle;
        this.config = config;
        this.gateway = gateway;
        this.storage = storage;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${loan.jobs.initial-delay:2s}", fixedDelayString = "${loan.jobs.outbox-delay:2s}")
    public void relay() {
        if (enabled && config.ready()) {
            outbox.publishPending();
        }
    }

    @Scheduled(initialDelayString = "${loan.jobs.initial-delay:2s}", fixedDelayString = "${loan.jobs.lifecycle-delay:30s}")
    public void lifecycleSweep() {
        if (!enabled || !config.ready() || !lease("lifecycle")) {
            return;
        }
        abandon();
        stuckReceived();
        orphans();
        retention();
        integrity();
    }

    public int abandon() {
        Period period = config.current().abandonAfter();
        long seconds = period.getYears() * 365L * 86400L + period.getMonths() * 30L * 86400L + period.getDays() * 86400L;
        return lifecycle.abandonExpired(seconds);
    }

    public int stuckReceived() {
        Duration stuck = config.current().receivedStuckAfter();
        Integer count = gateway.jdbc().queryForObject("""
                select count(*) from loan_application
                where status = 'RECEIVED' and created_at < now() - (? * interval '1 second')
                """, Integer.class, stuck.getSeconds());
        if (count != null && count > 0) {
            log.error("StuckBeforeCredit count={}", count);
        }
        return count == null ? 0 : count;
    }

    @Transactional
    public int orphans() {
        int deleted = 0;
        var pending = gateway.jdbc().query("""
                select object_key from pending_object where created_at < now() - interval '1 hour'
                """, (rs, n) -> rs.getString(1));
        for (String key : pending) {
            storage.delete(key);
            gateway.jdbc().update("delete from pending_object where object_key = ?", key);
            gateway.audit("ORPHAN_OBJECT_DELETED", "object", key, "SUCCESS", null, gatewayJson(key));
            deleted++;
        }
        HashSet<String> referenced = new HashSet<>();
        gateway.jdbc().query("select object_key from document where object_key is not null",
                (rs, n) -> referenced.add(rs.getString(1)));
        gateway.jdbc().query("select object_key from pending_object",
                (rs, n) -> referenced.add(rs.getString(1)));
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        for (DocumentStoragePort.Stored stored : storage.list()) {
            if (stored.modifiedAt().isBefore(cutoff) && !referenced.contains(stored.objectKey())) {
                storage.delete(stored.objectKey());
                gateway.audit("ORPHAN_OBJECT_DELETED", "object", stored.objectKey(), "SUCCESS", null, gatewayJson(stored.objectKey()));
                deleted++;
            }
        }
        return deleted;
    }

    public int retention() {
        if (!config.current().retentionEnforced()) {
            return 0;
        }
        var ids = gateway.jdbc().query("""
                select id from loan_application
                where created_at < now() - (? * interval '1 day') and wrapped_dek <> ?
                """, (rs, n) -> rs.getObject(1, UUID.class), config.current().retentionApplicationDays(), new byte[]{0});
        int count = 0;
        for (UUID id : ids) {
            gateway.audit("RETENTION_ERASED", "application", id.toString(), "SUCCESS", "RETENTION", null);
            gateway.jdbc().update("update extraction_run set raw_output_enc = null where application_id = ?", id);
            gateway.jdbc().update("update decision set salary_compared = null where application_id = ?", id);
            gateway.jdbc().update("update extraction_field set salary_monthly = null, gross_amount = null where extraction_run_id in (select id from extraction_run where application_id = ?)", id);
            gateway.jdbc().update("""
                    update loan_application
                    set wrapped_dek = ?, first_name_enc = ?, last_name_enc = ?, ssn_enc = ?, dob_enc = ?, email_enc = ?, phone_enc = ?, address_enc = ?
                    where id = ?
                    """, new byte[]{0}, new byte[]{0}, new byte[]{0}, new byte[]{0}, new byte[]{0}, new byte[]{0}, new byte[]{0}, new byte[]{0}, id);
            count++;
        }
        return count;
    }

    public int integrity() {
        var ids = gateway.jdbc().query("""
                select id::text from loan_application a
                where a.status = 'APPROVED'
                  and not exists (select 1 from decision d where d.application_id = a.id and d.outcome = 'APPROVED')
                """, (rs, n) -> rs.getString(1));
        String sample;
        try {
            sample = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ids);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            sample = "[]";
        }
        gateway.jdbc().update("insert into integrity_report (id, ran_at, violation_count, sample_ids) values (?, now(), ?, ?)",
                UUID.randomUUID(), ids.size(), gateway.jsonb(sample));
        if (!ids.isEmpty()) {
            log.error("integrity_violation count={}", ids.size());
        }
        return ids.size();
    }

    private boolean lease(String name) {
        var rows = gateway.jdbc().query("""
                insert into scheduler_lease (name, owner, lease_until)
                values (?, ?, now() + interval '30 seconds')
                on conflict (name) do update
                set owner = excluded.owner, lease_until = excluded.lease_until
                where scheduler_lease.lease_until < now() or scheduler_lease.owner = excluded.owner
                returning name
                """, (rs, n) -> rs.getString(1), name, owner);
        return !rows.isEmpty();
    }

    private com.fasterxml.jackson.databind.node.ObjectNode gatewayJson(String key) {
        return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("objectKey", key);
    }

    public long pipelineAgeSeconds(ApplicationStatus status) {
        var value = gateway.jdbc().query("""
                select extract(epoch from (now() - min(updated_at))) from loan_application where status = ?
                """, (rs, n) -> rs.getDouble(1), status.name());
        if (value.isEmpty() || rsNull(value.get(0))) {
            return 0;
        }
        return value.get(0).longValue();
    }

    private static boolean rsNull(Double value) {
        return value == null || value.isNaN();
    }
}
