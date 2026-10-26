package com.loanprocessing.decision;

import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.model.DecisionOutcome;
import com.loanprocessing.model.ReasonCode;

import java.math.BigDecimal;
import java.util.List;

public final class DecisionResult {
    private final ApplicationStatus status;
    private final ReasonCode reason;
    private final boolean writeDecisionRow;
    private final DecisionOutcome outcome;
    private final String ruleCode;
    private final List<String> secondaryRuleCodes;
    private final BigDecimal salaryCompared;
    private final BigDecimal threshold;
    private final String comparison;
    private final long configGeneration;
    private final String source;
    private final Mint mint;

    DecisionResult(
            ApplicationStatus status,
            ReasonCode reason,
            boolean writeDecisionRow,
            DecisionOutcome outcome,
            String ruleCode,
            List<String> secondaryRuleCodes,
            BigDecimal salaryCompared,
            BigDecimal threshold,
            String comparison,
            long configGeneration,
            String source,
            Mint mint
    ) {
        if (mint == null) {
            throw new IllegalArgumentException("decision was not minted");
        }
        if (status == ApplicationStatus.APPROVED) {
            if (outcome != DecisionOutcome.APPROVED || !("AUTO".equals(source) || "OPERATOR".equals(source))) {
                throw new IllegalArgumentException("approval is not authorized");
            }
        }
        this.status = status;
        this.reason = reason;
        this.writeDecisionRow = writeDecisionRow;
        this.outcome = outcome;
        this.ruleCode = ruleCode;
        this.secondaryRuleCodes = secondaryRuleCodes == null ? List.of() : List.copyOf(secondaryRuleCodes);
        this.salaryCompared = salaryCompared;
        this.threshold = threshold;
        this.comparison = comparison;
        this.configGeneration = configGeneration;
        this.source = source;
        this.mint = mint;
    }

    public ApplicationStatus status() { return status; }
    public ReasonCode reason() { return reason; }
    public boolean writeDecisionRow() { return writeDecisionRow; }
    public DecisionOutcome outcome() { return outcome; }
    public String ruleCode() { return ruleCode; }
    public List<String> secondaryRuleCodes() { return secondaryRuleCodes; }
    public BigDecimal salaryCompared() { return salaryCompared; }
    public BigDecimal threshold() { return threshold; }
    public String comparison() { return comparison; }
    public long configGeneration() { return configGeneration; }
    public String source() { return source; }

    public boolean approves() {
        return status == ApplicationStatus.APPROVED;
    }
}
