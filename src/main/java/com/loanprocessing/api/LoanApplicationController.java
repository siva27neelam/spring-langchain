package com.loanprocessing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanprocessing.intake.ApplicationIntakeService;
import com.loanprocessing.intake.IdempotencyService;
import com.loanprocessing.lifecycle.ApplicationLifecycle;
import com.loanprocessing.persistence.JdbcGateway;
import com.loanprocessing.persistence.JdbcGateway.ApplicationRow;
import com.loanprocessing.support.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/applications")
public class LoanApplicationController {
    private final ApplicationIntakeService intake;
    private final ApplicationLifecycle lifecycle;
    private final JdbcGateway gateway;
    private final ObjectMapper mapper;

    public LoanApplicationController(ApplicationIntakeService intake, ApplicationLifecycle lifecycle, JdbcGateway gateway, ObjectMapper mapper) {
        this.intake = intake;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<ObjectNode> create(Authentication authentication, @RequestHeader("Idempotency-Key") String key, @RequestBody ObjectNode body) {
        IdempotencyService.Completed completed = intake.submit(authentication.getName(), key, body);
        return ResponseEntity.status(completed.status()).body((ObjectNode) completed.body());
    }

    @GetMapping("/{id}")
    public ObjectNode get(Authentication authentication, @PathVariable UUID id) {
        return view(loadOwned(authentication, id), operator(authentication));
    }

    @GetMapping
    public Map<String, Object> list(Authentication authentication, @RequestParam(required = false) String status, @RequestParam(defaultValue = "20") int limit) {
        int size = Math.min(Math.max(limit, 1), 100);
        List<ObjectNode> items = new ArrayList<>();
        if (operator(authentication)) {
            gateway.jdbc().query(status == null
                            ? "select id from loan_application order by created_at desc limit ?"
                            : "select id from loan_application where status = ? order by created_at desc limit ?",
                    rs -> {
                        items.add(view(gateway.find(rs.getObject(1, UUID.class)).orElseThrow(), true));
                    }, status == null ? new Object[]{size} : new Object[]{status, size});
        } else {
            gateway.jdbc().query("select id from loan_application where customer_principal = ? order by created_at desc limit ?",
                    rs -> {
                        items.add(view(gateway.find(rs.getObject(1, UUID.class)).orElseThrow(), false));
                    }, authentication.getName(), size);
        }
        return Map.of("items", items);
    }

    @PostMapping("/{id}/withdrawal")
    public ResponseEntity<Map<String, Object>> withdraw(Authentication authentication, @PathVariable UUID id) {
        var result = lifecycle.withdraw(id, authentication.getName(), operator(authentication));
        return ResponseEntity.status(202).body(Map.of(
                "id", id.toString(),
                "status", result.status().name(),
                "withdrawRequested", result.withdrawRequested()
        ));
    }

    @GetMapping("/{id}/decisions")
    public List<Map<String, Object>> decisions(Authentication authentication, @PathVariable UUID id) {
        loadOwned(authentication, id);
        boolean operator = operator(authentication);
        List<Map<String, Object>> rows = new ArrayList<>();
        gateway.jdbc().query("""
                select source, outcome, rule_code, salary_compared, created_at from decision where application_id = ? order by created_at
                """, rs -> {
            if (operator) {
                rows.add(Map.of(
                        "source", rs.getString(1),
                        "outcome", rs.getString(2),
                        "ruleCode", rs.getString(3),
                        "salaryCompared", rs.getBigDecimal(4) == null ? "" : rs.getBigDecimal(4).toPlainString(),
                        "createdAt", rs.getTimestamp(5).toInstant().toString()
                ));
            } else {
                rows.add(Map.of(
                        "source", rs.getString(1),
                        "outcome", rs.getString(2),
                        "ruleCode", rs.getString(3),
                        "createdAt", rs.getTimestamp(5).toInstant().toString()
                ));
            }
        }, id);
        return rows;
    }

    private ApplicationRow loadOwned(Authentication authentication, UUID id) {
        ApplicationRow row = gateway.find(id).orElseThrow(() -> new ApiException(404, "NOT_FOUND", "application not found"));
        if (!operator(authentication) && !row.customerPrincipal().equals(authentication.getName())) {
            throw new ApiException(404, "NOT_FOUND", "application not found");
        }
        return row;
    }

    private ObjectNode view(ApplicationRow row, boolean operator) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", row.id().toString());
        node.put("status", row.status().name());
        if (row.statusReason() == null) {
            node.putNull("statusReason");
        } else {
            node.put("statusReason", row.statusReason());
        }
        node.put("createdAt", row.createdAt().toString());
        node.put("updatedAt", row.updatedAt().toString());
        node.put("maskedSsn", "***-**-" + row.ssnLast4());
        node.put("withdrawRequested", row.withdrawRequested());
        gateway.jdbc().query("select score, score_scale from credit_report where application_id = ?", rs -> {
            node.put("creditScore", rs.getInt(1));
            node.put("creditScoreScale", rs.getString(2));
        }, row.id());
        var documents = node.putArray("documents");
        gateway.jdbc().query("select id::text, media_type, byte_size, status from document where application_id = ? order by created_at", rs -> {
            ObjectNode document = documents.addObject();
            document.put("id", rs.getString(1));
            document.put("mediaType", rs.getString(2));
            document.put("byteSize", rs.getLong(3));
            document.put("status", rs.getString(4));
        }, row.id());
        gateway.jdbc().query("""
                select outcome, rule_code, salary_compared from decision where application_id = ? order by created_at desc limit 1
                """, rs -> {
            ObjectNode decision = node.putObject("decision");
            decision.put("outcome", rs.getString(1));
            decision.put("ruleCode", rs.getString(2));
            if (rs.getBigDecimal(3) != null) {
                decision.put("salaryCompared", rs.getBigDecimal(3).toPlainString());
            }
        }, row.id());
        return node;
    }

    static boolean operator(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_LOAN_OPERATOR".equals(authority.getAuthority()) || "ROLE_LOAN_COMPLIANCE".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
