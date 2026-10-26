package com.loanprocessing.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.model.ActorType;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.security.RequestContext;
import com.loanprocessing.support.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RuntimeConfigurationService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigurationService.class);
    private final JdbcGateway gateway;
    private final ObjectMapper mapper;
    private final ConfigValidator validator;
    private final boolean fourEyes;
    private final AtomicReference<ConfigSnapshot> current = new AtomicReference<>();
    private final AtomicInteger refreshFailures = new AtomicInteger();
    private volatile OffsetDateTime refreshedAt = OffsetDateTime.now(ZoneOffset.UTC);

    public RuntimeConfigurationService(
            JdbcGateway gateway,
            ObjectMapper mapper,
            @Value("${loan.production:false}") boolean production,
            @Value("${loan.config.four-eyes-enabled:true}") boolean fourEyes,
            org.springframework.beans.factory.ObjectProvider<com.loanprocessing.document.ImageOcrPort> ocr
    ) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.validator = new ConfigValidator(mapper, production, ocr.getIfAvailable() != null);
        this.fourEyes = fourEyes;
    }

    public ConfigSnapshot current() {
        ConfigSnapshot snapshot = current.get();
        if (snapshot == null) {
            throw new ApiException(503, "CONFIG_INVALID", "configuration is not ready");
        }
        return snapshot;
    }

    public boolean ready() {
        ConfigSnapshot snapshot = current.get();
        return snapshot != null && snapshot.valid();
    }

    public int refreshFailures() {
        return refreshFailures.get();
    }

    public OffsetDateTime refreshedAt() {
        return refreshedAt;
    }

    public boolean fourEyesEnabled() {
        return fourEyes;
    }

    public void loadOrThrow() {
        ConfigSnapshot snapshot = read();
        if (!snapshot.valid()) {
            throw new IllegalStateException(snapshot.invalidReason());
        }
        current.set(snapshot);
        refreshedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @org.springframework.scheduling.annotation.Scheduled(initialDelayString = "${loan.jobs.initial-delay:15s}", fixedDelayString = "${loan.jobs.refresh-delay:15s}")
    public void refresh() {
        try {
            ConfigSnapshot snapshot = read();
            if (!snapshot.valid()) {
                throw new IllegalStateException(snapshot.invalidReason());
            }
            current.set(snapshot);
            refreshedAt = OffsetDateTime.now(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            refreshFailures.incrementAndGet();
            log.warn("CONFIG_REFRESH_FAILED");
        }
    }

    @Transactional
    public UUID propose(String key, JsonNode value, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(422, "VALIDATION", "reason is required");
        }
        if (!ConfigCatalog.defaults().containsKey(key)) {
            throw new ApiException(422, "CONFIG_INVALID", "unknown configuration key");
        }
        Map<String, String> raw = new LinkedHashMap<>(current().rawJson());
        raw.put(key, value.toString());
        ConfigSnapshot prospective = validator.parse(current().generation(), raw);
        if (!prospective.valid()) {
            throw new ApiException(422, "CONFIG_INVALID", prospective.invalidReason());
        }
        UUID id = UUID.randomUUID();
        long ttl = current().proposalTtl() == null ? 86400 : current().proposalTtl().getSeconds();
        gateway.jdbc().update("""
                insert into config_change_proposal (id, config_key, value_json, reason, proposed_by, status, created_at, expires_at, correlation_id)
                values (?, ?, ?, ?, ?, 'PENDING', now(), now() + (? * interval '1 second'), ?)
                """, id, key, gateway.jsonb(value.toString()), reason, actor, ttl, RequestContext.get().correlationId());
        ObjectNode meta = mapper.createObjectNode();
        meta.put("key", key);
        gateway.audit("CONFIG_CHANGE_PROPOSED", "config", key, "SUCCESS", null, meta);
        if (!ConfigCatalog.sensitive(key) || !fourEyes) {
            approve(id, actor, true);
        }
        return id;
    }

    @Transactional
    public ConfigSnapshot approve(UUID proposalId, String actor) {
        return approve(proposalId, actor, false);
    }

    private ConfigSnapshot approve(UUID proposalId, String actor, boolean sameCallerAllowed) {
        var rows = gateway.jdbc().query("""
                select config_key, value_json::text, reason, proposed_by, status, expires_at < now() as expired
                from config_change_proposal where id = ? for update
                """, (rs, n) -> new Proposal(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6)), proposalId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "NOT_FOUND", "proposal not found");
        }
        Proposal proposal = rows.get(0);
        if (!"PENDING".equals(proposal.status()) && !sameCallerAllowed) {
            throw new ApiException(409, "PROPOSAL_CLOSED", "proposal is not pending");
        }
        if (proposal.expired()) {
            gateway.jdbc().update("update config_change_proposal set status = 'EXPIRED' where id = ?", proposalId);
            throw new ApiException(409, "PROPOSAL_EXPIRED", "proposal has expired");
        }
        boolean sensitive = ConfigCatalog.sensitive(proposal.key());
        if (sensitive && fourEyes && proposal.proposedBy().equals(actor)) {
            throw new ApiException(409, "FOUR_EYES", "the proposer cannot approve this change");
        }
        if (!sensitive && !sameCallerAllowed && !proposal.proposedBy().equals(actor) && fourEyes) {
            // a second admin may still publish a non-sensitive key
        }
        Map<String, String> raw = new LinkedHashMap<>(loadRaw());
        String old = raw.get(proposal.key());
        raw.put(proposal.key(), proposal.valueJson());
        long next = gateway.jdbc().queryForObject("update config_generation set generation = generation + 1 returning generation", Long.class);
        ConfigSnapshot snapshot = validator.parse(next, raw);
        if (!snapshot.valid()) {
            throw new ApiException(422, "CONFIG_INVALID", snapshot.invalidReason());
        }
        gateway.jdbc().update("""
                update runtime_config set value_json = ?, version = version + 1, updated_at = now(), updated_by = ? where key = ?
                """, gateway.jsonb(proposal.valueJson()), actor, proposal.key());
        gateway.jdbc().update("""
                insert into config_change_audit (id, config_key, old_value, new_value, proposed_by, approved_by, reason, correlation_id, generation, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), proposal.key(), gateway.jsonb(old), gateway.jsonb(proposal.valueJson()), proposal.proposedBy(), actor,
                proposal.reason(), RequestContext.get().correlationId(), next);
        gateway.jdbc().update("update config_change_proposal set status = 'PUBLISHED', approved_by = ? where id = ?", actor, proposalId);
        ObjectNode meta = mapper.createObjectNode();
        meta.put("key", proposal.key());
        meta.put("generation", next);
        meta.put("newValue", proposal.valueJson());
        RequestContext.Ids previous = RequestContext.get();
        RequestContext.set(new RequestContext.Ids(previous.correlationId(), previous.requestId(), actor, ActorType.CONFIG_ADMIN));
        gateway.audit("CONFIG_CHANGED", "config", proposal.key(), "SUCCESS", null, meta);
        if ("decision.salary.threshold".equals(proposal.key()) || "decision.salary.comparison".equals(proposal.key())) {
            log.info("UnderwritingRuleChanged key={} generation={}", proposal.key(), next);
        } else {
            log.info("config_published key={} generation={}", proposal.key(), next);
        }
        current.set(snapshot);
        refreshedAt = OffsetDateTime.now(ZoneOffset.UTC);
        return snapshot;
    }

    private ConfigSnapshot read() {
        Map<String, String> raw = loadRaw();
        long generation = gateway.jdbc().queryForObject("select generation from config_generation", Long.class);
        return validator.parse(generation, raw);
    }

    private Map<String, String> loadRaw() {
        Map<String, String> raw = new LinkedHashMap<>();
        gateway.jdbc().query("select key, value_json::text from runtime_config", rs -> {
            raw.put(rs.getString(1), rs.getString(2));
        });
        return raw;
    }

    public Map<String, String> raw() {
        return Map.copyOf(current().rawJson());
    }

    private record Proposal(String key, String valueJson, String reason, String proposedBy, String status, boolean expired) {
    }
}
