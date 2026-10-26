package com.loanprocessing.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanprocessing.model.ComparisonOperator;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.support.ApiException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigValidator {
    private static final Set<String> COMPARISONS = Set.of(
            "GREATER_THAN", "GREATER_THAN_OR_EQUAL", "LESS_THAN", "LESS_THAN_OR_EQUAL");
    private static final Set<String> AGGREGATIONS = Set.of("ANY_EXCEEDS", "MAX", "MIN", "LATEST_PERIOD");
    private static final Set<String> MEDIA = Set.of("application/pdf", "image/jpeg", "image/png");

    private final ObjectMapper mapper;
    private final boolean production;
    private final boolean ocrAdapterBound;

    public ConfigValidator(ObjectMapper mapper, boolean production, boolean ocrAdapterBound) {
        this.mapper = mapper;
        this.production = production;
        this.ocrAdapterBound = ocrAdapterBound;
    }

    public ConfigSnapshot parse(long generation, Map<String, String> raw) {
        List<String> errors = new ArrayList<>();
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        for (String key : ConfigCatalog.defaults().keySet()) {
            String json = raw.get(key);
            if (json == null) {
                errors.add(key + " is missing");
                continue;
            }
            try {
                nodes.put(key, mapper.readTree(json));
            } catch (Exception e) {
                errors.add(key + " is not json");
            }
        }
        if (!errors.isEmpty()) {
            return ConfigSnapshot.invalid(generation, String.join("; ", errors), raw);
        }
        try {
            return build(generation, raw, nodes, errors);
        } catch (RuntimeException e) {
            return ConfigSnapshot.invalid(generation, e.getMessage(), raw);
        }
    }

    public void requireValid(ConfigSnapshot snapshot) {
        if (!snapshot.valid()) {
            throw new ApiException(422, "CONFIG_INVALID", snapshot.invalidReason());
        }
    }

    private ConfigSnapshot build(long generation, Map<String, String> raw, Map<String, JsonNode> nodes, List<String> errors) {
        BigDecimal threshold = decimal(nodes, "decision.salary.threshold", errors);
        BigDecimal sanity = decimal(nodes, "decision.salary.sanity-max", errors);
        BigDecimal tolerance = decimal(nodes, "decision.salary.conflict-tolerance-amount", errors);
        BigDecimal minSalary = decimal(nodes, "decision.confidence.min-salary", errors);
        BigDecimal minIdentity = decimal(nodes, "decision.confidence.min-identity", errors);
        BigDecimal overlap = decimal(nodes, "decision.identity.address-token-overlap", errors);
        BigDecimal temperature = decimal(nodes, "llm.temperature", errors);
        String comparison = text(nodes, "decision.salary.comparison", errors);
        String currency = text(nodes, "decision.salary.currency", errors);
        String aggregation = text(nodes, "decision.salary.aggregation", errors);
        String mismatch = text(nodes, "decision.identity.mismatch-action", errors);
        String uncertainty = text(nodes, "decision.uncertainty.action", errors);
        String provider = text(nodes, "credit.provider", errors);
        String cutoffComparison = text(nodes, "credit.cutoff.comparison", errors);
        String llmProvider = text(nodes, "llm.provider", errors);
        String baseUrl = text(nodes, "llm.base-url", errors);
        String model = text(nodes, "llm.model", errors);
        String country = text(nodes, "intake.allowed-country", errors);
        boolean cutoff = bool(nodes, "credit.cutoff.enabled", errors);
        boolean ocr = bool(nodes, "documents.ocr.enabled", errors);
        boolean scanRequired = bool(nodes, "documents.scan.required", errors);
        boolean auto = bool(nodes, "extraction.auto-decision.enabled", errors);
        boolean logRequests = bool(nodes, "llm.log-requests", errors);
        boolean singleOpen = bool(nodes, "intake.single-open-application-per-person", errors);
        boolean retention = bool(nodes, "retention.enforced", errors);
        boolean operator = bool(nodes, "features.operator-decision.enabled", errors);
        Integer cutoffMinimum = nullableInt(nodes, "credit.cutoff.minimum-score", errors);
        int stub = integer(nodes, "credit.stub.score", errors);
        int required = integer(nodes, "documents.payslip.required-count", errors);
        int maxCount = integer(nodes, "documents.payslip.max-count", errors);
        int maxBytes = integer(nodes, "documents.payslip.max-bytes", errors);
        int maxPages = integer(nodes, "documents.payslip.max-pages", errors);
        int maxRejected = integer(nodes, "documents.payslip.max-rejected", errors);
        int parseTimeout = integer(nodes, "documents.parse.timeout-seconds", errors);
        int maxChars = integer(nodes, "extraction.max-input-chars", errors);
        int prompt = integer(nodes, "llm.prompt.version", errors);
        int timeout = integer(nodes, "llm.timeout.seconds", errors);
        int retries = integer(nodes, "llm.max-retries", errors);
        int outputTokens = integer(nodes, "llm.max-output-tokens", errors);
        int circuitFailures = integer(nodes, "llm.circuit.failure-threshold", errors);
        int circuitOpen = integer(nodes, "llm.circuit.open-seconds", errors);
        int creditAttempts = integer(nodes, "lifecycle.credit.retry-max-attempts", errors);
        int extractionAttempts = integer(nodes, "lifecycle.extraction.retry-max-attempts", errors);
        int intakeRate = integer(nodes, "api.intake.requests-per-minute", errors);
        int uploadRate = integer(nodes, "api.upload.requests-per-minute", errors);
        int minAge = integer(nodes, "intake.min-age-years", errors);
        int wait = integer(nodes, "idempotency.wait-seconds", errors);
        int retentionDays = integer(nodes, "retention.application.days", errors);
        int rawDays = integer(nodes, "retention.raw-model-output.days", errors);
        Duration holdAfter = duration(nodes, "llm.circuit.hold-after", errors);
        Period abandon = period(nodes, "lifecycle.payslip-review.abandon-after", errors);
        Duration stuck = duration(nodes, "lifecycle.received.stuck-after", errors);
        Period holdSla = period(nodes, "lifecycle.manual-hold.sla", errors);
        Duration creditLease = duration(nodes, "lifecycle.lease.credit", errors);
        Duration extractionLease = duration(nodes, "lifecycle.lease.extraction", errors);
        Duration idempotencyTtl = duration(nodes, "idempotency.ttl", errors);
        Duration proposalTtl = duration(nodes, "config.proposal.ttl", errors);
        List<String> media = stringList(nodes, "documents.payslip.allowed-media-types", errors);

        if (threshold != null && (threshold.signum() <= 0 || (sanity != null && threshold.compareTo(sanity) > 0))) {
            errors.add("decision.salary.threshold is out of range");
        }
        if (sanity != null && threshold != null && sanity.compareTo(threshold) <= 0) {
            errors.add("decision.salary.sanity-max must exceed threshold");
        }
        if (tolerance != null && tolerance.signum() < 0) {
            errors.add("conflict tolerance must be >= 0");
        }
        requireEnum(comparison, COMPARISONS, "decision.salary.comparison", errors);
        if (currency != null && !"USD".equals(currency)) {
            errors.add("decision.salary.currency must be USD");
        }
        requireEnum(aggregation, AGGREGATIONS, "decision.salary.aggregation", errors);
        if (mismatch != null && !mismatch.equals("DECLINE") && !mismatch.equals("MANUAL_HOLD")) {
            errors.add("decision.identity.mismatch-action cannot approve");
        }
        if (uncertainty != null && !uncertainty.equals("MANUAL_HOLD") && !uncertainty.equals("DECLINE")) {
            errors.add("decision.uncertainty.action cannot be APPROVED");
        }
        unitInterval(minSalary, "decision.confidence.min-salary", errors);
        unitInterval(minIdentity, "decision.confidence.min-identity", errors);
        unitInterval(overlap, "decision.identity.address-token-overlap", errors);
        if (provider != null && !"STUB".equals(provider)) {
            errors.add("credit.provider EXPERIAN is not available in this build");
        }
        if (stub < 0 || stub > 850) {
            errors.add("credit.stub.score is out of range");
        }
        if (cutoff && cutoffMinimum == null) {
            errors.add("credit.cutoff.minimum-score is required when cutoff is enabled");
        }
        requireEnum(cutoffComparison, COMPARISONS, "credit.cutoff.comparison", errors);
        if (required < 1 || maxCount < required || maxCount > 12) {
            errors.add("payslip counts are inconsistent");
        }
        if (maxBytes < 1 || maxBytes > 20971520) {
            errors.add("documents.payslip.max-bytes is out of range");
        }
        if (maxPages < 1 || maxPages > 30) {
            errors.add("documents.payslip.max-pages is out of range");
        }
        if (maxRejected < 1 || maxRejected > 50) {
            errors.add("documents.payslip.max-rejected is out of range");
        }
        if (media != null) {
            if (!MEDIA.containsAll(media)) {
                errors.add("media type is not allowed");
            }
            boolean image = media.stream().anyMatch(type -> type.startsWith("image/"));
            if (image && !ocr) {
                errors.add("image media types require OCR");
            }
        }
        if (parseTimeout < 1 || parseTimeout > 60) {
            errors.add("documents.parse.timeout-seconds is out of range");
        }
        if (ocr && !ocrAdapterBound) {
            errors.add("documents.ocr.enabled requires an OCR adapter");
        }
        if (production && !scanRequired) {
            errors.add("documents.scan.required cannot be false in production");
        }
        if (maxChars < 1000 || maxChars > 200000) {
            errors.add("extraction.max-input-chars is out of range");
        }
        if (llmProvider != null && !"OPENAI_COMPATIBLE".equals(llmProvider)) {
            errors.add("llm.provider is unknown");
        }
        if (baseUrl != null && !baseUrl.isBlank() && !baseUrl.startsWith("https://")) {
            errors.add("llm.base-url must be https");
        }
        if (prompt != 1) {
            errors.add("llm.prompt.version is not shipped");
        }
        if (timeout < 5 || timeout > 120) {
            errors.add("llm.timeout.seconds is out of range");
        }
        if (retries < 0 || retries > 4) {
            errors.add("llm.max-retries is out of range");
        }
        if (temperature == null || temperature.compareTo(BigDecimal.ZERO) != 0) {
            errors.add("llm.temperature must stay 0");
        }
        if (outputTokens < 256 || outputTokens > 4096) {
            errors.add("llm.max-output-tokens is out of range");
        }
        if (production && logRequests) {
            errors.add("llm.log-requests cannot be true in production");
        }
        if (circuitFailures < 1) {
            errors.add("llm.circuit.failure-threshold is out of range");
        }
        if (circuitOpen < 1) {
            errors.add("llm.circuit.open-seconds is out of range");
        }
        if (abandon != null) {
            long days = abandon.getYears() * 365L + abandon.getMonths() * 30L + abandon.getDays();
            if (days < 1) {
                errors.add("abandon TTL must be at least P1D");
            }
        }
        if (creditAttempts < 1 || extractionAttempts < 1) {
            errors.add("retry budget must be at least 1");
        }
        if (extractionLease != null && timeout > 0) {
            long needed = (long) timeout * (1L + Math.max(retries, 0)) + parseTimeout + 5;
            if (extractionLease.getSeconds() <= needed) {
                errors.add("extraction lease must exceed the model timeout budget");
            }
        }
        if (minAge < 0 || minAge > 120) {
            errors.add("intake.min-age-years is out of range");
        }
        if (country != null && !"US".equals(country)) {
            errors.add("intake.allowed-country must be US in this build");
        }
        if (wait < 0 || wait > 30) {
            errors.add("idempotency.wait-seconds is out of range");
        }
        if (!errors.isEmpty()) {
            return ConfigSnapshot.invalid(generation, String.join("; ", errors), raw);
        }
        return ConfigSnapshot.builder()
                .generation(generation)
                .valid(true)
                .rawJson(raw)
                .salaryThreshold(threshold)
                .salaryComparison(ComparisonOperator.valueOf(comparison))
                .salaryCurrency(currency)
                .salaryAggregation(aggregation)
                .conflictTolerance(tolerance)
                .sanityMax(sanity)
                .mismatchAction(mismatch)
                .uncertaintyAction(uncertainty)
                .minSalaryConfidence(minSalary)
                .minIdentityConfidence(minIdentity)
                .addressTokenOverlap(overlap)
                .creditProvider(provider)
                .stubScore(stub)
                .cutoffEnabled(cutoff)
                .cutoffMinimum(cutoffMinimum)
                .cutoffComparison(ComparisonOperator.valueOf(cutoffComparison))
                .requiredPayslips(required)
                .maxPayslips(maxCount)
                .maxBytes(maxBytes)
                .maxPages(maxPages)
                .maxRejected(maxRejected)
                .ocrEnabled(ocr)
                .scanRequired(scanRequired)
                .maxInputChars(maxChars)
                .autoDecisionEnabled(auto)
                .llmModel(model)
                .llmBaseUrl(baseUrl == null ? "" : baseUrl)
                .promptVersion(prompt)
                .llmTimeoutSeconds(timeout)
                .llmMaxRetries(retries)
                .llmMaxOutputTokens(outputTokens)
                .llmLogRequests(logRequests)
                .circuitFailureThreshold(circuitFailures)
                .circuitOpenSeconds(circuitOpen)
                .circuitHoldAfter(holdAfter)
                .abandonAfter(abandon)
                .receivedStuckAfter(stuck)
                .manualHoldSla(holdSla)
                .creditRetryMax(creditAttempts)
                .extractionRetryMax(extractionAttempts)
                .creditLease(creditLease)
                .extractionLease(extractionLease)
                .intakePerMinute(intakeRate)
                .uploadPerMinute(uploadRate)
                .minAgeYears(minAge)
                .singleOpenApplication(singleOpen)
                .allowedCountry(country)
                .idempotencyTtl(idempotencyTtl)
                .idempotencyWaitSeconds(wait)
                .retentionEnforced(retention)
                .retentionApplicationDays(retentionDays)
                .retentionRawOutputDays(rawDays)
                .proposalTtl(proposalTtl)
                .operatorDecisionEnabled(operator)
                .parseTimeoutSeconds(parseTimeout)
                .build();
    }

    private static void requireEnum(String value, Set<String> allowed, String key, List<String> errors) {
        if (value == null || !allowed.contains(value)) {
            errors.add(key + " is not a legal value");
        }
    }

    private static void unitInterval(BigDecimal value, String key, List<String> errors) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            errors.add(key + " must be between 0 and 1");
        }
    }

    private static BigDecimal decimal(Map<String, JsonNode> nodes, String key, List<String> errors) {
        JsonNode node = nodes.get(key);
        if (node == null || !node.isNumber()) {
            errors.add(key + " must be a number");
            return null;
        }
        return node.decimalValue();
    }

    private static int integer(Map<String, JsonNode> nodes, String key, List<String> errors) {
        JsonNode node = nodes.get(key);
        if (node == null || !node.isIntegralNumber()) {
            errors.add(key + " must be an integer");
            return 0;
        }
        return node.intValue();
    }

    private static Integer nullableInt(Map<String, JsonNode> nodes, String key, List<String> errors) {
        JsonNode node = nodes.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            errors.add(key + " must be an integer or null");
            return null;
        }
        return node.intValue();
    }

    private static boolean bool(Map<String, JsonNode> nodes, String key, List<String> errors) {
        JsonNode node = nodes.get(key);
        if (node == null || !node.isBoolean()) {
            errors.add(key + " must be a boolean");
            return false;
        }
        return node.booleanValue();
    }

    private static String text(Map<String, JsonNode> nodes, String key, List<String> errors) {
        JsonNode node = nodes.get(key);
        if (node == null || !node.isTextual()) {
            errors.add(key + " must be a string");
            return null;
        }
        return node.asText();
    }

    private static Duration duration(Map<String, JsonNode> nodes, String key, List<String> errors) {
        String text = text(nodes, key, errors);
        if (text == null) {
            return null;
        }
        try {
            return Duration.parse(text);
        } catch (Exception e) {
            errors.add(key + " must be an ISO-8601 duration");
            return null;
        }
    }

    private static Period period(Map<String, JsonNode> nodes, String key, List<String> errors) {
        String text = text(nodes, key, errors);
        if (text == null) {
            return null;
        }
        try {
            return Period.parse(text);
        } catch (Exception e) {
            errors.add(key + " must be an ISO-8601 period");
            return null;
        }
    }

    private static List<String> stringList(Map<String, JsonNode> nodes, String key, List<String> errors) {
        JsonNode node = nodes.get(key);
        if (node == null || !node.isArray()) {
            errors.add(key + " must be a list");
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode child : node) {
            if (!child.isTextual()) {
                errors.add(key + " must contain strings");
                return List.of();
            }
            values.add(child.asText());
        }
        return List.copyOf(values);
    }
}
