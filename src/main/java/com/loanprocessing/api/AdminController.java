package com.loanprocessing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.config.ConfigCatalog;
import com.loanprocessing.config.RuntimeConfigurationService;
import com.loanprocessing.decision.DecisionService;
import com.loanprocessing.jobs.LeaseAndAbandonJobs;
import com.loanprocessing.lifecycle.ApplicationLifecycle;
import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.security.FieldEncryptor;
import com.loanprocessing.security.KeyManagementPort;
import com.loanprocessing.support.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private static final Set<String> OPERATOR_REASONS = Set.of(
            "OPERATOR_IDENTITY_CONFIRMED", "OPERATOR_IDENTITY_REJECTED", "OPERATOR_INCOME_ACCEPTED",
            "OPERATOR_INCOME_REJECTED", "OPERATOR_POLICY");
    private final ApplicationLifecycle lifecycle;
    private final RuntimeConfigurationService config;
    private final DecisionService decisions;
    private final JdbcGateway gateway;
    private final FieldEncryptor encryptor;
    private final KeyManagementPort kms;
    private final LeaseAndAbandonJobs jobs;
    private final ObjectMapper mapper;

    public AdminController(ApplicationLifecycle lifecycle, RuntimeConfigurationService config, DecisionService decisions,
                           JdbcGateway gateway, FieldEncryptor encryptor, KeyManagementPort kms, LeaseAndAbandonJobs jobs, ObjectMapper mapper) {
        this.lifecycle = lifecycle;
        this.config = config;
        this.decisions = decisions;
        this.gateway = gateway;
        this.encryptor = encryptor;
        this.kms = kms;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @PostMapping("/applications/{id}/retries")
    public ResponseEntity<Map<String, String>> retry(Authentication authentication, @PathVariable UUID id, @RequestBody ObjectNode body) {
        require(authentication, "ROLE_LOAN_OPERATOR");
        String step = body.path("step").asText();
        int budget = "CREDIT".equals(step) ? config.current().creditRetryMax() : config.current().extractionRetryMax();
        lifecycle.scheduleRetry(id, step, budget);
        return ResponseEntity.status(202).body(Map.of("status", "accepted"));
    }

    @PostMapping("/applications/{id}/manual-decisions")
    public ResponseEntity<Map<String, String>> manual(Authentication authentication, @PathVariable UUID id, @RequestBody ObjectNode body) {
        require(authentication, "ROLE_LOAN_OPERATOR");
        if (!config.current().operatorDecisionEnabled()) {
            throw new ApiException(403, "FORBIDDEN", "operator decisions are disabled");
        }
        String outcome = body.path("outcome").asText();
        String reason = body.path("reasonCode").asText();
        String comment = body.path("comment").asText();
        if (!OPERATOR_REASONS.contains(reason) || comment.isBlank()) {
            throw new ApiException(422, "VALIDATION", "operator reason and comment are required");
        }
        ApplicationStatus status = "APPROVED".equals(outcome) ? ApplicationStatus.APPROVED : "DECLINED".equals(outcome) ? ApplicationStatus.DECLINED : null;
        if (status == null) {
            throw new ApiException(422, "VALIDATION", "outcome is invalid");
        }
        var result = decisions.operator(status, reason, config.current());
        lifecycle.operatorResolve(id, result, () -> gateway.insertDecision(id, null, result, authentication.getName(), commentCipher(id, comment)));
        return ResponseEntity.ok(Map.of("status", outcome));
    }

    @GetMapping("/runtime-config")
    public Map<String, String> config(Authentication authentication) {
        require(authentication, "ROLE_CONFIG_ADMIN");
        return config.raw();
    }

    @GetMapping("/runtime-config/{key}")
    public Map<String, String> configKey(Authentication authentication, @PathVariable String key) {
        require(authentication, "ROLE_CONFIG_ADMIN");
        String value = config.raw().get(key);
        if (value == null) {
            throw new ApiException(404, "NOT_FOUND", "unknown configuration key");
        }
        return Map.of("key", key, "value", value, "sensitive", Boolean.toString(ConfigCatalog.sensitive(key)));
    }

    @PostMapping("/runtime-config/proposals")
    public Map<String, String> propose(Authentication authentication, @RequestBody ObjectNode body) {
        require(authentication, "ROLE_CONFIG_ADMIN");
        JsonNode value = body.get("value");
        UUID id = config.propose(body.path("key").asText(), value, body.path("reason").asText(), authentication.getName());
        return Map.of("id", id.toString());
    }

    @PostMapping("/runtime-config/proposals/{id}/approval")
    public Map<String, Object> approve(Authentication authentication, @PathVariable UUID id) {
        require(authentication, "ROLE_CONFIG_ADMIN");
        var snapshot = config.approve(id, authentication.getName());
        return Map.of("generation", snapshot.generation(), "valid", snapshot.valid());
    }

    @GetMapping("/applications/{id}/audit")
    public List<Map<String, Object>> audit(Authentication authentication, @PathVariable UUID id) {
        if (!has(authentication, "ROLE_LOAN_OPERATOR") && !has(authentication, "ROLE_LOAN_COMPLIANCE")) {
            throw new ApiException(403, "FORBIDDEN", "forbidden");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        gateway.jdbc().query("""
                select action, outcome, reason_code, occurred_at, metadata::text from audit_event
                where resource_id = ? order by occurred_at
                """, rs -> {
            rows.add(Map.of(
                    "action", rs.getString(1),
                    "outcome", rs.getString(2),
                    "reasonCode", String.valueOf(rs.getString(3)),
                    "occurredAt", rs.getTimestamp(4).toInstant().toString(),
                    "metadata", String.valueOf(rs.getString(5))
            ));
        }, id.toString());
        return rows;
    }

    @PostMapping("/applications/{id}/reveal")
    public Map<String, String> reveal(Authentication authentication, @PathVariable UUID id, @RequestBody ObjectNode body) {
        require(authentication, "ROLE_LOAN_COMPLIANCE");
        String reason = body.path("reason").asText();
        String field = body.path("field").asText();
        if (reason.isBlank() || (!"ssn".equals(field) && !"dateOfBirth".equals(field))) {
            throw new ApiException(422, "VALIDATION", "reason and field are required");
        }
        var row = gateway.find(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "application not found"));
        var open = encryptor.open(row.wrappedDek(), row.encryptionKeyVersion(), row.firstNameEnc(), row.lastNameEnc(),
                row.ssnEnc(), row.dobEnc(), row.emailEnc(), row.phoneEnc(), row.addressEnc());
        gateway.audit("BREAK_GLASS_REVEAL", "application", id.toString(), "SUCCESS", reason, mapper.createObjectNode().put("field", field));
        String value = "ssn".equals(field) ? open.ssnDigits() : open.dateOfBirth().toString();
        return Map.of("field", field, "value", value);
    }

    @GetMapping("/ops/pipeline")
    public List<Map<String, Object>> pipeline(Authentication authentication) {
        require(authentication, "ROLE_LOAN_OPERATOR");
        List<Map<String, Object>> rows = new ArrayList<>();
        gateway.jdbc().query("""
                select status, count(*), coalesce(extract(epoch from (now() - min(updated_at))), 0)
                from loan_application group by status
                """, rs -> {
            rows.add(Map.of("status", rs.getString(1), "count", rs.getInt(2), "oldestAgeSeconds", rs.getLong(3)));
        });
        return rows;
    }

    @GetMapping("/ops/decisions")
    public Map<String, Object> decisionMix(Authentication authentication) {
        require(authentication, "ROLE_LOAN_OPERATOR");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threshold", config.current().salaryThreshold().toPlainString());
        body.put("comparison", config.current().salaryComparison().name());
        body.put("provider", config.current().creditProvider());
        List<Map<String, Object>> rows = new ArrayList<>();
        gateway.jdbc().query("select outcome, rule_code, count(*) from decision group by outcome, rule_code", rs -> {
            rows.add(Map.of("outcome", rs.getString(1), "rule", rs.getString(2), "count", rs.getInt(3)));
        });
        body.put("counts", rows);
        return body;
    }

    @GetMapping("/ops/integrity")
    public Map<String, Object> integrity(Authentication authentication) {
        require(authentication, "ROLE_LOAN_OPERATOR");
        int violations = jobs.integrity();
        return Map.of("violations", violations);
    }

    private byte[] commentCipher(UUID id, String comment) {
        var row = gateway.find(id).orElseThrow();
        byte[] dek = kms.unwrap(row.wrappedDek(), row.encryptionKeyVersion());
        try {
            return encryptor.encryptWith(dek, comment);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private static void require(Authentication authentication, String role) {
        if (!has(authentication, role)) {
            throw new ApiException(403, "FORBIDDEN", "forbidden");
        }
    }

    private static boolean has(Authentication authentication, String role) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (role.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
