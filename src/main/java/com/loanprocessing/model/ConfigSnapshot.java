package com.loanprocessing.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Period;
import java.util.Map;

/**
 * Immutable business-knob snapshot. Decision code reads this object and does not re-read live config.
 */
public final class ConfigSnapshot {
    private final long generation;
    private final boolean valid;
    private final String invalidReason;
    private final Map<String, String> rawJson;
    private final BigDecimal salaryThreshold;
    private final ComparisonOperator salaryComparison;
    private final String salaryCurrency;
    private final String salaryAggregation;
    private final BigDecimal conflictTolerance;
    private final BigDecimal sanityMax;
    private final String mismatchAction;
    private final String uncertaintyAction;
    private final BigDecimal minSalaryConfidence;
    private final BigDecimal minIdentityConfidence;
    private final BigDecimal addressTokenOverlap;
    private final String creditProvider;
    private final int stubScore;
    private final boolean cutoffEnabled;
    private final Integer cutoffMinimum;
    private final ComparisonOperator cutoffComparison;
    private final int requiredPayslips;
    private final int maxPayslips;
    private final int maxBytes;
    private final int maxPages;
    private final int maxRejected;
    private final boolean ocrEnabled;
    private final boolean scanRequired;
    private final int maxInputChars;
    private final boolean autoDecisionEnabled;
    private final String llmModel;
    private final String llmBaseUrl;
    private final int promptVersion;
    private final int llmTimeoutSeconds;
    private final int llmMaxRetries;
    private final int llmMaxOutputTokens;
    private final boolean llmLogRequests;
    private final int circuitFailureThreshold;
    private final int circuitOpenSeconds;
    private final Duration circuitHoldAfter;
    private final Period abandonAfter;
    private final Duration receivedStuckAfter;
    private final Period manualHoldSla;
    private final int creditRetryMax;
    private final int extractionRetryMax;
    private final Duration creditLease;
    private final Duration extractionLease;
    private final int intakePerMinute;
    private final int uploadPerMinute;
    private final int minAgeYears;
    private final boolean singleOpenApplication;
    private final String allowedCountry;
    private final Duration idempotencyTtl;
    private final int idempotencyWaitSeconds;
    private final boolean retentionEnforced;
    private final int retentionApplicationDays;
    private final int retentionRawOutputDays;
    private final Duration proposalTtl;
    private final boolean operatorDecisionEnabled;
    private final int parseTimeoutSeconds;

    private ConfigSnapshot(Builder b) {
        this.generation = b.generation;
        this.valid = b.valid;
        this.invalidReason = b.invalidReason;
        this.rawJson = b.rawJson == null ? Map.of() : Map.copyOf(b.rawJson);
        this.salaryThreshold = b.salaryThreshold;
        this.salaryComparison = b.salaryComparison;
        this.salaryCurrency = b.salaryCurrency;
        this.salaryAggregation = b.salaryAggregation;
        this.conflictTolerance = b.conflictTolerance;
        this.sanityMax = b.sanityMax;
        this.mismatchAction = b.mismatchAction;
        this.uncertaintyAction = b.uncertaintyAction;
        this.minSalaryConfidence = b.minSalaryConfidence;
        this.minIdentityConfidence = b.minIdentityConfidence;
        this.addressTokenOverlap = b.addressTokenOverlap;
        this.creditProvider = b.creditProvider;
        this.stubScore = b.stubScore;
        this.cutoffEnabled = b.cutoffEnabled;
        this.cutoffMinimum = b.cutoffMinimum;
        this.cutoffComparison = b.cutoffComparison;
        this.requiredPayslips = b.requiredPayslips;
        this.maxPayslips = b.maxPayslips;
        this.maxBytes = b.maxBytes;
        this.maxPages = b.maxPages;
        this.maxRejected = b.maxRejected;
        this.ocrEnabled = b.ocrEnabled;
        this.scanRequired = b.scanRequired;
        this.maxInputChars = b.maxInputChars;
        this.autoDecisionEnabled = b.autoDecisionEnabled;
        this.llmModel = b.llmModel;
        this.llmBaseUrl = b.llmBaseUrl;
        this.promptVersion = b.promptVersion;
        this.llmTimeoutSeconds = b.llmTimeoutSeconds;
        this.llmMaxRetries = b.llmMaxRetries;
        this.llmMaxOutputTokens = b.llmMaxOutputTokens;
        this.llmLogRequests = b.llmLogRequests;
        this.circuitFailureThreshold = b.circuitFailureThreshold;
        this.circuitOpenSeconds = b.circuitOpenSeconds;
        this.circuitHoldAfter = b.circuitHoldAfter;
        this.abandonAfter = b.abandonAfter;
        this.receivedStuckAfter = b.receivedStuckAfter;
        this.manualHoldSla = b.manualHoldSla;
        this.creditRetryMax = b.creditRetryMax;
        this.extractionRetryMax = b.extractionRetryMax;
        this.creditLease = b.creditLease;
        this.extractionLease = b.extractionLease;
        this.intakePerMinute = b.intakePerMinute;
        this.uploadPerMinute = b.uploadPerMinute;
        this.minAgeYears = b.minAgeYears;
        this.singleOpenApplication = b.singleOpenApplication;
        this.allowedCountry = b.allowedCountry;
        this.idempotencyTtl = b.idempotencyTtl;
        this.idempotencyWaitSeconds = b.idempotencyWaitSeconds;
        this.retentionEnforced = b.retentionEnforced;
        this.retentionApplicationDays = b.retentionApplicationDays;
        this.retentionRawOutputDays = b.retentionRawOutputDays;
        this.proposalTtl = b.proposalTtl;
        this.operatorDecisionEnabled = b.operatorDecisionEnabled;
        this.parseTimeoutSeconds = b.parseTimeoutSeconds;
    }

    public static ConfigSnapshot invalid(long generation, String reason, Map<String, String> rawJson) {
        Builder b = new Builder();
        b.generation = generation;
        b.valid = false;
        b.invalidReason = reason;
        b.rawJson = rawJson;
        return new ConfigSnapshot(b);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean modelConfigured() {
        return llmModel != null && !llmModel.isBlank() && !"unconfigured".equalsIgnoreCase(llmModel);
    }

    public long generation() { return generation; }
    public boolean valid() { return valid; }
    public String invalidReason() { return invalidReason; }
    public Map<String, String> rawJson() { return rawJson; }
    public BigDecimal salaryThreshold() { return salaryThreshold; }
    public ComparisonOperator salaryComparison() { return salaryComparison; }
    public String salaryCurrency() { return salaryCurrency; }
    public String salaryAggregation() { return salaryAggregation; }
    public BigDecimal conflictTolerance() { return conflictTolerance; }
    public BigDecimal sanityMax() { return sanityMax; }
    public String mismatchAction() { return mismatchAction; }
    public String uncertaintyAction() { return uncertaintyAction; }
    public BigDecimal minSalaryConfidence() { return minSalaryConfidence; }
    public BigDecimal minIdentityConfidence() { return minIdentityConfidence; }
    public BigDecimal addressTokenOverlap() { return addressTokenOverlap; }
    public String creditProvider() { return creditProvider; }
    public int stubScore() { return stubScore; }
    public boolean cutoffEnabled() { return cutoffEnabled; }
    public Integer cutoffMinimum() { return cutoffMinimum; }
    public ComparisonOperator cutoffComparison() { return cutoffComparison; }
    public int requiredPayslips() { return requiredPayslips; }
    public int maxPayslips() { return maxPayslips; }
    public int maxBytes() { return maxBytes; }
    public int maxPages() { return maxPages; }
    public int maxRejected() { return maxRejected; }
    public boolean ocrEnabled() { return ocrEnabled; }
    public boolean scanRequired() { return scanRequired; }
    public int maxInputChars() { return maxInputChars; }
    public boolean autoDecisionEnabled() { return autoDecisionEnabled; }
    public String llmModel() { return llmModel; }
    public String llmBaseUrl() { return llmBaseUrl; }
    public int promptVersion() { return promptVersion; }
    public int llmTimeoutSeconds() { return llmTimeoutSeconds; }
    public int llmMaxRetries() { return llmMaxRetries; }
    public int llmMaxOutputTokens() { return llmMaxOutputTokens; }
    public boolean llmLogRequests() { return llmLogRequests; }
    public int circuitFailureThreshold() { return circuitFailureThreshold; }
    public int circuitOpenSeconds() { return circuitOpenSeconds; }
    public Duration circuitHoldAfter() { return circuitHoldAfter; }
    public Period abandonAfter() { return abandonAfter; }
    public Duration receivedStuckAfter() { return receivedStuckAfter; }
    public Period manualHoldSla() { return manualHoldSla; }
    public int creditRetryMax() { return creditRetryMax; }
    public int extractionRetryMax() { return extractionRetryMax; }
    public Duration creditLease() { return creditLease; }
    public Duration extractionLease() { return extractionLease; }
    public int intakePerMinute() { return intakePerMinute; }
    public int uploadPerMinute() { return uploadPerMinute; }
    public int minAgeYears() { return minAgeYears; }
    public boolean singleOpenApplication() { return singleOpenApplication; }
    public String allowedCountry() { return allowedCountry; }
    public Duration idempotencyTtl() { return idempotencyTtl; }
    public int idempotencyWaitSeconds() { return idempotencyWaitSeconds; }
    public boolean retentionEnforced() { return retentionEnforced; }
    public int retentionApplicationDays() { return retentionApplicationDays; }
    public int retentionRawOutputDays() { return retentionRawOutputDays; }
    public Duration proposalTtl() { return proposalTtl; }
    public boolean operatorDecisionEnabled() { return operatorDecisionEnabled; }
    public int parseTimeoutSeconds() { return parseTimeoutSeconds; }

    public static final class Builder {
        private long generation;
        private boolean valid = true;
        private String invalidReason;
        private Map<String, String> rawJson = Map.of();
        private BigDecimal salaryThreshold;
        private ComparisonOperator salaryComparison;
        private String salaryCurrency;
        private String salaryAggregation;
        private BigDecimal conflictTolerance;
        private BigDecimal sanityMax;
        private String mismatchAction;
        private String uncertaintyAction;
        private BigDecimal minSalaryConfidence;
        private BigDecimal minIdentityConfidence;
        private BigDecimal addressTokenOverlap;
        private String creditProvider;
        private int stubScore;
        private boolean cutoffEnabled;
        private Integer cutoffMinimum;
        private ComparisonOperator cutoffComparison;
        private int requiredPayslips;
        private int maxPayslips;
        private int maxBytes;
        private int maxPages;
        private int maxRejected;
        private boolean ocrEnabled;
        private boolean scanRequired = true;
        private int maxInputChars;
        private boolean autoDecisionEnabled = true;
        private String llmModel;
        private String llmBaseUrl = "";
        private int promptVersion;
        private int llmTimeoutSeconds;
        private int llmMaxRetries;
        private int llmMaxOutputTokens;
        private boolean llmLogRequests;
        private int circuitFailureThreshold;
        private int circuitOpenSeconds;
        private Duration circuitHoldAfter;
        private Period abandonAfter;
        private Duration receivedStuckAfter;
        private Period manualHoldSla;
        private int creditRetryMax;
        private int extractionRetryMax;
        private Duration creditLease;
        private Duration extractionLease;
        private int intakePerMinute;
        private int uploadPerMinute;
        private int minAgeYears;
        private boolean singleOpenApplication;
        private String allowedCountry;
        private Duration idempotencyTtl;
        private int idempotencyWaitSeconds;
        private boolean retentionEnforced;
        private int retentionApplicationDays;
        private int retentionRawOutputDays;
        private Duration proposalTtl;
        private boolean operatorDecisionEnabled = true;
        private int parseTimeoutSeconds = 10;

        public Builder generation(long v) { this.generation = v; return this; }
        public Builder valid(boolean v) { this.valid = v; return this; }
        public Builder invalidReason(String v) { this.invalidReason = v; return this; }
        public Builder rawJson(Map<String, String> v) { this.rawJson = v; return this; }
        public Builder salaryThreshold(BigDecimal v) { this.salaryThreshold = v; return this; }
        public Builder salaryComparison(ComparisonOperator v) { this.salaryComparison = v; return this; }
        public Builder salaryCurrency(String v) { this.salaryCurrency = v; return this; }
        public Builder salaryAggregation(String v) { this.salaryAggregation = v; return this; }
        public Builder conflictTolerance(BigDecimal v) { this.conflictTolerance = v; return this; }
        public Builder sanityMax(BigDecimal v) { this.sanityMax = v; return this; }
        public Builder mismatchAction(String v) { this.mismatchAction = v; return this; }
        public Builder uncertaintyAction(String v) { this.uncertaintyAction = v; return this; }
        public Builder minSalaryConfidence(BigDecimal v) { this.minSalaryConfidence = v; return this; }
        public Builder minIdentityConfidence(BigDecimal v) { this.minIdentityConfidence = v; return this; }
        public Builder addressTokenOverlap(BigDecimal v) { this.addressTokenOverlap = v; return this; }
        public Builder creditProvider(String v) { this.creditProvider = v; return this; }
        public Builder stubScore(int v) { this.stubScore = v; return this; }
        public Builder cutoffEnabled(boolean v) { this.cutoffEnabled = v; return this; }
        public Builder cutoffMinimum(Integer v) { this.cutoffMinimum = v; return this; }
        public Builder cutoffComparison(ComparisonOperator v) { this.cutoffComparison = v; return this; }
        public Builder requiredPayslips(int v) { this.requiredPayslips = v; return this; }
        public Builder maxPayslips(int v) { this.maxPayslips = v; return this; }
        public Builder maxBytes(int v) { this.maxBytes = v; return this; }
        public Builder maxPages(int v) { this.maxPages = v; return this; }
        public Builder maxRejected(int v) { this.maxRejected = v; return this; }
        public Builder ocrEnabled(boolean v) { this.ocrEnabled = v; return this; }
        public Builder scanRequired(boolean v) { this.scanRequired = v; return this; }
        public Builder maxInputChars(int v) { this.maxInputChars = v; return this; }
        public Builder autoDecisionEnabled(boolean v) { this.autoDecisionEnabled = v; return this; }
        public Builder llmModel(String v) { this.llmModel = v; return this; }
        public Builder llmBaseUrl(String v) { this.llmBaseUrl = v; return this; }
        public Builder promptVersion(int v) { this.promptVersion = v; return this; }
        public Builder llmTimeoutSeconds(int v) { this.llmTimeoutSeconds = v; return this; }
        public Builder llmMaxRetries(int v) { this.llmMaxRetries = v; return this; }
        public Builder llmMaxOutputTokens(int v) { this.llmMaxOutputTokens = v; return this; }
        public Builder llmLogRequests(boolean v) { this.llmLogRequests = v; return this; }
        public Builder circuitFailureThreshold(int v) { this.circuitFailureThreshold = v; return this; }
        public Builder circuitOpenSeconds(int v) { this.circuitOpenSeconds = v; return this; }
        public Builder circuitHoldAfter(Duration v) { this.circuitHoldAfter = v; return this; }
        public Builder abandonAfter(Period v) { this.abandonAfter = v; return this; }
        public Builder receivedStuckAfter(Duration v) { this.receivedStuckAfter = v; return this; }
        public Builder manualHoldSla(Period v) { this.manualHoldSla = v; return this; }
        public Builder creditRetryMax(int v) { this.creditRetryMax = v; return this; }
        public Builder extractionRetryMax(int v) { this.extractionRetryMax = v; return this; }
        public Builder creditLease(Duration v) { this.creditLease = v; return this; }
        public Builder extractionLease(Duration v) { this.extractionLease = v; return this; }
        public Builder intakePerMinute(int v) { this.intakePerMinute = v; return this; }
        public Builder uploadPerMinute(int v) { this.uploadPerMinute = v; return this; }
        public Builder minAgeYears(int v) { this.minAgeYears = v; return this; }
        public Builder singleOpenApplication(boolean v) { this.singleOpenApplication = v; return this; }
        public Builder allowedCountry(String v) { this.allowedCountry = v; return this; }
        public Builder idempotencyTtl(Duration v) { this.idempotencyTtl = v; return this; }
        public Builder idempotencyWaitSeconds(int v) { this.idempotencyWaitSeconds = v; return this; }
        public Builder retentionEnforced(boolean v) { this.retentionEnforced = v; return this; }
        public Builder retentionApplicationDays(int v) { this.retentionApplicationDays = v; return this; }
        public Builder retentionRawOutputDays(int v) { this.retentionRawOutputDays = v; return this; }
        public Builder proposalTtl(Duration v) { this.proposalTtl = v; return this; }
        public Builder operatorDecisionEnabled(boolean v) { this.operatorDecisionEnabled = v; return this; }
        public Builder parseTimeoutSeconds(int v) { this.parseTimeoutSeconds = v; return this; }

        public ConfigSnapshot build() {
            return new ConfigSnapshot(this);
        }
    }
}
