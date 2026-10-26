package com.loanprocessing.decision;

import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.model.ComparisonOperator;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.model.DecisionOutcome;
import com.loanprocessing.model.ReasonCode;
import com.loanprocessing.model.TechnicalSignal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The only component allowed to mint an automatic APPROVED or DECLINED outcome.
 * Salary comparison is the specified inverse rule: the configured comparison firing declines.
 * Equal to the default threshold does not fire GREATER_THAN, so 7000.00 can approve.
 */
public final class DecisionService {

    public DecisionResult decide(DecisionFacts facts, ConfigSnapshot snapshot) {
        if (facts.withdrawRequested()) {
            return terminal(ApplicationStatus.WITHDRAWN, ReasonCode.CUSTOMER_WITHDREW, false, null, null,
                    List.of(), null, snapshot, "AUTO");
        }
        long readable = facts.payslips().stream().filter(p -> !p.unreadable()).count();
        boolean anyPayslip = !facts.payslips().isEmpty();
        if (anyPayslip && readable < facts.requiredCount()) {
            return noDecision(ApplicationStatus.PAYSLIP_REVIEW, null, snapshot);
        }
        if (facts.technical() != TechnicalSignal.NONE) {
            return technical(facts, snapshot);
        }
        if (snapshot == null || !snapshot.valid()) {
            return hold(ReasonCode.CONFIG_INVALID, snapshot, null);
        }
        if (!snapshot.autoDecisionEnabled()) {
            return hold(ReasonCode.AUTO_DECISION_DISABLED, snapshot, null);
        }
        if (facts.payslips().stream().anyMatch(p -> p.identity() == com.loanprocessing.model.IdentityResult.MISMATCH)) {
            List<String> secondary = salaryWouldFire(facts, snapshot) ? List.of(ReasonCode.SALARY_RULE.name()) : List.of();
            return mismatch(snapshot, secondary);
        }
        String uncertainty = uncertaintyReason(facts, snapshot);
        if (uncertainty != null) {
            return uncertainty(snapshot, ReasonCode.valueOf(uncertainty));
        }
        BigDecimal compared = aggregate(facts, snapshot);
        boolean fires = snapshot.salaryComparison().fires(compared, snapshot.salaryThreshold());
        if (fires) {
            return salary(ApplicationStatus.DECLINED, DecisionOutcome.DECLINED, compared, snapshot);
        }
        return salary(ApplicationStatus.APPROVED, DecisionOutcome.APPROVED, compared, snapshot);
    }

    public DecisionResult credit(Integer score, ConfigSnapshot snapshot) {
        if (snapshot == null || !snapshot.valid()) {
            return hold(ReasonCode.CONFIG_INVALID, snapshot, null);
        }
        if (score == null) {
            return hold(ReasonCode.CREDIT_SCORE_MISSING, snapshot, null);
        }
        if (snapshot.cutoffEnabled()) {
            if (snapshot.cutoffMinimum() == null || snapshot.cutoffComparison() == null) {
                return hold(ReasonCode.CONFIG_INVALID, snapshot, null);
            }
            if (snapshot.cutoffComparison().fires(score, snapshot.cutoffMinimum())) {
                return terminal(ApplicationStatus.DECLINED, ReasonCode.CREDIT_BELOW_CUTOFF, true,
                        DecisionOutcome.DECLINED, ReasonCode.CREDIT_BELOW_CUTOFF.name(), List.of(),
                        null, snapshot, "AUTO");
            }
        }
        return noDecision(ApplicationStatus.PAYSLIP_REVIEW, ReasonCode.CREDIT_RECORDED, snapshot);
    }

    public DecisionResult holdFor(ReasonCode reason, ConfigSnapshot snapshot) {
        return hold(reason, snapshot, null);
    }

    public DecisionResult operator(ApplicationStatus outcome, String operatorReason, ConfigSnapshot snapshot) {
        if (outcome != ApplicationStatus.APPROVED && outcome != ApplicationStatus.DECLINED) {
            throw new IllegalArgumentException("operator outcome must approve or decline");
        }
        if (operatorReason == null || operatorReason.isBlank()) {
            throw new IllegalArgumentException("operator reason is required");
        }
        DecisionOutcome mapped = outcome == ApplicationStatus.APPROVED ? DecisionOutcome.APPROVED : DecisionOutcome.DECLINED;
        return terminal(outcome, ReasonCode.OPERATOR_DECISION, true, mapped, ReasonCode.OPERATOR_DECISION.name(),
                List.of(operatorReason), null, snapshot, "OPERATOR");
    }

    private DecisionResult technical(DecisionFacts facts, ConfigSnapshot snapshot) {
        return switch (facts.technical()) {
            case MODEL_NOT_CONFIGURED -> hold(ReasonCode.MODEL_NOT_CONFIGURED, snapshot, null);
            case CIRCUIT_HOLD -> hold(ReasonCode.MODEL_PROVIDER_UNAVAILABLE, snapshot, null);
            case SCHEMA_INVALID -> hold(ReasonCode.EXTRACTION_UNCERTAIN, snapshot, null);
            case POISON -> hold(ReasonCode.PROCESSING_POISON, snapshot, null);
            case DECRYPT_FAILED -> facts.attemptsRemain()
                    ? retry(snapshot)
                    : hold(ReasonCode.CONFIG_INVALID, snapshot, null);
            case BUDGET_EXHAUSTED -> failed(snapshot);
            case RETRYABLE -> facts.attemptsRemain() ? retry(snapshot) : failed(snapshot);
            case CIRCUIT_OPEN -> new DecisionResult(ApplicationStatus.EXTRACTION_QUEUED, null, false, null, null,
                    List.of(), null, null, null, facts.configGeneration(), "AUTO", Mint.get());
            case NONE -> throw new IllegalStateException("none");
        };
    }

    private DecisionResult mismatch(ConfigSnapshot snapshot, List<String> secondary) {
        if ("MANUAL_HOLD".equals(snapshot.mismatchAction())) {
            return hold(ReasonCode.IDENTITY_MISMATCH, snapshot, secondary);
        }
        return terminal(ApplicationStatus.DECLINED, ReasonCode.IDENTITY_MISMATCH, true,
                DecisionOutcome.DECLINED, ReasonCode.IDENTITY_MISMATCH.name(), secondary,
                null, snapshot, "AUTO");
    }

    private DecisionResult uncertainty(ConfigSnapshot snapshot, ReasonCode reason) {
        if ("DECLINE".equals(snapshot.uncertaintyAction())) {
            return terminal(ApplicationStatus.DECLINED, reason, true, DecisionOutcome.DECLINED, reason.name(),
                    List.of(), null, snapshot, "AUTO");
        }
        return hold(reason, snapshot, null);
    }

    private static String uncertaintyReason(DecisionFacts facts, ConfigSnapshot snapshot) {
        if (facts.payslips().isEmpty()) {
            return ReasonCode.EXTRACTION_UNCERTAIN.name();
        }
        for (PayslipView payslip : facts.payslips()) {
            if (payslip.unreadable()) {
                return ReasonCode.EXTRACTION_UNCERTAIN.name();
            }
            if (payslip.identity() != com.loanprocessing.model.IdentityResult.MATCH) {
                return ReasonCode.EXTRACTION_UNCERTAIN.name();
            }
            if (!payslip.salaryCertain() || payslip.monthlyAmount() == null) {
                if (payslip.uncertainReasons().contains("PAYSLIP_CONFLICT")) {
                    return ReasonCode.PAYSLIP_CONFLICT.name();
                }
                return ReasonCode.EXTRACTION_UNCERTAIN.name();
            }
        }
        if (facts.requiredCount() > 1 && conflicts(facts, snapshot)) {
            return ReasonCode.PAYSLIP_CONFLICT.name();
        }
        return null;
    }

    private static boolean conflicts(DecisionFacts facts, ConfigSnapshot snapshot) {
        List<BigDecimal> amounts = certainAmounts(facts);
        if (amounts.size() < 2) {
            return false;
        }
        BigDecimal min = amounts.stream().min(Comparator.naturalOrder()).orElseThrow();
        BigDecimal max = amounts.stream().max(Comparator.naturalOrder()).orElseThrow();
        BigDecimal tolerance = snapshot.conflictTolerance() == null
                ? new BigDecimal("1.00")
                : snapshot.conflictTolerance();
        return max.subtract(min).abs().compareTo(tolerance) > 0;
    }

    private boolean salaryWouldFire(DecisionFacts facts, ConfigSnapshot snapshot) {
        if (snapshot == null || !snapshot.valid() || snapshot.salaryComparison() == null || snapshot.salaryThreshold() == null) {
            return false;
        }
        List<BigDecimal> amounts = certainAmounts(facts);
        if (amounts.isEmpty()) {
            return false;
        }
        return amounts.stream().anyMatch(amount -> snapshot.salaryComparison().fires(amount, snapshot.salaryThreshold()));
    }

    private static List<BigDecimal> certainAmounts(DecisionFacts facts) {
        List<BigDecimal> amounts = new ArrayList<>();
        for (PayslipView payslip : facts.payslips()) {
            if (payslip.salaryCertain() && payslip.monthlyAmount() != null) {
                amounts.add(payslip.monthlyAmount());
            }
        }
        return amounts;
    }

    private static BigDecimal aggregate(DecisionFacts facts, ConfigSnapshot snapshot) {
        List<PayslipView> ready = facts.payslips().stream()
                .filter(p -> p.salaryCertain() && p.monthlyAmount() != null)
                .toList();
        String aggregation = snapshot.salaryAggregation() == null ? "ANY_EXCEEDS" : snapshot.salaryAggregation();
        if (facts.requiredCount() <= 1 || "ANY_EXCEEDS".equals(aggregation)) {
            if ("ANY_EXCEEDS".equals(aggregation) && facts.requiredCount() > 1) {
                BigDecimal firing = null;
                BigDecimal highest = null;
                for (PayslipView payslip : ready) {
                    BigDecimal amount = payslip.monthlyAmount();
                    if (highest == null || amount.compareTo(highest) > 0) {
                        highest = amount;
                    }
                    if (snapshot.salaryComparison().fires(amount, snapshot.salaryThreshold())) {
                        firing = amount;
                        break;
                    }
                }
                return firing != null ? firing : highest;
            }
            return ready.get(0).monthlyAmount();
        }
        return switch (aggregation) {
            case "MAX" -> ready.stream().map(PayslipView::monthlyAmount).max(Comparator.naturalOrder()).orElseThrow();
            case "MIN" -> ready.stream().map(PayslipView::monthlyAmount).min(Comparator.naturalOrder()).orElseThrow();
            case "LATEST_PERIOD" -> latest(ready).monthlyAmount();
            default -> ready.get(0).monthlyAmount();
        };
    }

    private static PayslipView latest(List<PayslipView> ready) {
        return ready.stream().max(Comparator.comparing(p -> p.periodEnd() == null ? LocalDate.MIN : p.periodEnd())).orElseThrow();
    }

    private DecisionResult salary(ApplicationStatus status, DecisionOutcome outcome, BigDecimal compared, ConfigSnapshot snapshot) {
        BigDecimal scale = compared.setScale(2, RoundingMode.HALF_EVEN);
        return terminal(status, ReasonCode.SALARY_RULE, true, outcome, ReasonCode.SALARY_RULE.name(), List.of(),
                scale, snapshot, "AUTO");
    }

    private DecisionResult hold(ReasonCode reason, ConfigSnapshot snapshot, List<String> secondary) {
        return terminal(ApplicationStatus.MANUAL_HOLD, reason, true, DecisionOutcome.MANUAL_HOLD, reason.name(),
                secondary == null ? List.of() : secondary, null, snapshot, "AUTO");
    }

    private DecisionResult retry(ConfigSnapshot snapshot) {
        return noDecision(ApplicationStatus.EXTRACTION_QUEUED, ReasonCode.EXTRACTION_UNCERTAIN, snapshot);
    }

    private DecisionResult failed(ConfigSnapshot snapshot) {
        return noDecision(ApplicationStatus.EXTRACTION_FAILED, ReasonCode.EXTRACTION_RETRIES_EXHAUSTED, snapshot);
    }

    private DecisionResult noDecision(ApplicationStatus status, ReasonCode reason, ConfigSnapshot snapshot) {
        return new DecisionResult(status, reason, false, null, null, List.of(), null,
                snapshot == null ? null : snapshot.salaryThreshold(),
                snapshot == null || snapshot.salaryComparison() == null ? null : snapshot.salaryComparison().name(),
                snapshot == null ? 0 : snapshot.generation(), "AUTO", Mint.get());
    }

    private DecisionResult terminal(
            ApplicationStatus status,
            ReasonCode reason,
            boolean writeDecision,
            DecisionOutcome outcome,
            String rule,
            List<String> secondary,
            BigDecimal salary,
            ConfigSnapshot snapshot,
            String source
    ) {
        ComparisonOperator comparison = snapshot == null ? null : snapshot.salaryComparison();
        return new DecisionResult(status, reason, writeDecision, outcome, rule, secondary, salary,
                snapshot == null ? null : snapshot.salaryThreshold(),
                comparison == null ? null : comparison.name(),
                snapshot == null ? 0 : snapshot.generation(), source, Mint.get());
    }
}
