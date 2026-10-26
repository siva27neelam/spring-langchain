package com.loanprocessing.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ConfigCatalog {
    public static final Set<String> SENSITIVE = Set.of(
            "decision.salary.threshold",
            "decision.salary.comparison",
            "decision.salary.currency",
            "decision.salary.aggregation",
            "decision.identity.mismatch-action",
            "decision.uncertainty.action",
            "credit.provider",
            "credit.stub.score",
            "credit.cutoff.enabled",
            "credit.cutoff.minimum-score",
            "credit.cutoff.comparison",
            "llm.provider",
            "llm.base-url",
            "llm.model",
            "llm.prompt.version",
            "extraction.auto-decision.enabled",
            "documents.ocr.enabled",
            "intake.min-age-years",
            "intake.single-open-application-per-person",
            "intake.allowed-country",
            "retention.enforced",
            "retention.application.days",
            "retention.raw-model-output.days",
            "features.operator-decision.enabled"
    );

    private ConfigCatalog() {
    }

    public static Map<String, String> defaults() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("decision.salary.threshold", "7000");
        values.put("decision.salary.comparison", "\"GREATER_THAN\"");
        values.put("decision.salary.currency", "\"USD\"");
        values.put("decision.salary.aggregation", "\"ANY_EXCEEDS\"");
        values.put("decision.salary.conflict-tolerance-amount", "1.00");
        values.put("decision.salary.sanity-max", "1000000");
        values.put("decision.identity.mismatch-action", "\"DECLINE\"");
        values.put("decision.uncertainty.action", "\"MANUAL_HOLD\"");
        values.put("decision.confidence.min-salary", "0.85");
        values.put("decision.confidence.min-identity", "0.90");
        values.put("decision.identity.address-token-overlap", "0.80");
        values.put("credit.provider", "\"STUB\"");
        values.put("credit.stub.score", "600");
        values.put("credit.cutoff.enabled", "false");
        values.put("credit.cutoff.minimum-score", "null");
        values.put("credit.cutoff.comparison", "\"LESS_THAN\"");
        values.put("documents.payslip.required-count", "1");
        values.put("documents.payslip.max-count", "3");
        values.put("documents.payslip.max-bytes", "10485760");
        values.put("documents.payslip.max-pages", "8");
        values.put("documents.payslip.max-rejected", "10");
        values.put("documents.payslip.allowed-media-types", "[\"application/pdf\"]");
        values.put("documents.parse.timeout-seconds", "10");
        values.put("documents.ocr.enabled", "false");
        values.put("documents.scan.required", "true");
        values.put("extraction.max-input-chars", "50000");
        values.put("extraction.auto-decision.enabled", "true");
        values.put("llm.provider", "\"OPENAI_COMPATIBLE\"");
        values.put("llm.base-url", "\"\"");
        values.put("llm.model", "\"unconfigured\"");
        values.put("llm.prompt.version", "1");
        values.put("llm.timeout.seconds", "30");
        values.put("llm.max-retries", "2");
        values.put("llm.temperature", "0");
        values.put("llm.max-output-tokens", "1500");
        values.put("llm.log-requests", "false");
        values.put("llm.circuit.failure-threshold", "5");
        values.put("llm.circuit.open-seconds", "60");
        values.put("llm.circuit.hold-after", "\"PT30M\"");
        values.put("lifecycle.payslip-review.abandon-after", "\"P14D\"");
        values.put("lifecycle.received.stuck-after", "\"PT15M\"");
        values.put("lifecycle.manual-hold.sla", "\"P2D\"");
        values.put("lifecycle.credit.retry-max-attempts", "5");
        values.put("lifecycle.extraction.retry-max-attempts", "3");
        values.put("lifecycle.lease.credit", "\"PT2M\"");
        values.put("lifecycle.lease.extraction", "\"PT10M\"");
        values.put("api.intake.requests-per-minute", "10");
        values.put("api.upload.requests-per-minute", "20");
        values.put("intake.min-age-years", "18");
        values.put("intake.single-open-application-per-person", "false");
        values.put("intake.allowed-country", "\"US\"");
        values.put("idempotency.ttl", "\"PT24H\"");
        values.put("idempotency.wait-seconds", "3");
        values.put("retention.enforced", "false");
        values.put("retention.application.days", "2555");
        values.put("retention.raw-model-output.days", "30");
        values.put("config.proposal.ttl", "\"PT24H\"");
        values.put("features.operator-decision.enabled", "true");
        return Map.copyOf(values);
    }

    public static boolean sensitive(String key) {
        return SENSITIVE.contains(key);
    }
}
