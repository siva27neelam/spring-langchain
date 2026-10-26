package com.loanprocessing.decision;

import com.loanprocessing.model.ApplicationStatus;
import com.loanprocessing.model.ComparisonOperator;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.model.DecisionOutcome;
import com.loanprocessing.model.IdentityResult;
import com.loanprocessing.model.ReasonCode;
import com.loanprocessing.model.TechnicalSignal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionServiceTest {
    private final DecisionService service = new DecisionService();

    @Test
    void salaryEqualToThresholdCanApproveAndACentOverDeclines() {
        DecisionResult at = service.decide(facts(certain("7000.00")), defaults());
        DecisionResult under = service.decide(facts(certain("6999.99")), defaults());
        DecisionResult over = service.decide(facts(certain("7000.01")), defaults());

        assertEquals(ApplicationStatus.APPROVED, at.status());
        assertEquals(DecisionOutcome.APPROVED, at.outcome());
        assertEquals(ApplicationStatus.APPROVED, under.status());
        assertEquals(ApplicationStatus.DECLINED, over.status());
        assertEquals(ReasonCode.SALARY_RULE, over.reason());
        assertEquals(0, new BigDecimal("7000.01").compareTo(over.salaryCompared()));
        assertEquals(1L, over.configGeneration());
        assertTrue(at.approves());
        assertFalse(over.approves());
    }

    @Test
    void clearMismatchNeverApprovesEvenWhenSalaryWouldAlsoDecline() {
        DecisionResult mismatch = service.decide(facts(new PayslipView(
                false, IdentityResult.MISMATCH, true, new BigDecimal("8000.00"), null, List.of())), defaults());
        assertEquals(ApplicationStatus.DECLINED, mismatch.status());
        assertEquals(ReasonCode.IDENTITY_MISMATCH, mismatch.reason());
        assertTrue(mismatch.secondaryRuleCodes().contains(ReasonCode.SALARY_RULE.name()));
        assertFalse(mismatch.approves());
    }

    @Test
    void uncertainHighSalaryHoldsAndDoesNotApprove() {
        DecisionResult uncertain = service.decide(facts(new PayslipView(
                false, IdentityResult.UNCERTAIN, true, new BigDecimal("99999.00"), null, List.of("GROUNDING"))), defaults());
        assertEquals(ApplicationStatus.MANUAL_HOLD, uncertain.status());
        assertEquals(ReasonCode.EXTRACTION_UNCERTAIN, uncertain.reason());
        assertFalse(uncertain.approves());
    }

    @Test
    void conflictingPayslipsHold() {
        ConfigSnapshot snapshot = snapshot(true, 2, false, null);
        DecisionFacts conflict = new DecisionFacts(false, 2, List.of(certain("1000.00"), certain("1002.00")),
                TechnicalSignal.NONE, false, snapshot.generation());
        DecisionResult result = service.decide(conflict, snapshot);
        assertEquals(ApplicationStatus.MANUAL_HOLD, result.status());
        assertEquals(ReasonCode.PAYSLIP_CONFLICT, result.reason());
        assertFalse(result.approves());
    }

    @Test
    void withdrawalBeatsAnOtherwiseApprovablePayslip() {
        DecisionFacts facts = new DecisionFacts(true, 1, List.of(certain("1000.00")), TechnicalSignal.NONE, false, 1);
        DecisionResult result = service.decide(facts, defaults());
        assertEquals(ApplicationStatus.WITHDRAWN, result.status());
        assertFalse(result.writeDecisionRow());
        assertFalse(result.approves());
    }

    @Test
    void invalidConfigAndDisabledAutoDecisionHold() {
        DecisionResult invalid = service.decide(facts(certain("1000.00")), ConfigSnapshot.invalid(4, "broken", java.util.Map.of()));
        assertEquals(ApplicationStatus.MANUAL_HOLD, invalid.status());
        assertEquals(ReasonCode.CONFIG_INVALID, invalid.reason());

        ConfigSnapshot disabled = snapshot(false, 1, false, null);
        DecisionResult held = service.decide(facts(certain("1000.00")), disabled);
        assertEquals(ApplicationStatus.MANUAL_HOLD, held.status());
        assertEquals(ReasonCode.AUTO_DECISION_DISABLED, held.reason());
        assertFalse(held.approves());
    }

    @Test
    void nullCreditScoreHoldsAndCutoffDeclineStillKeepsTheComparison() {
        ConfigSnapshot open = defaults();
        DecisionResult recorded = service.credit(600, open);
        assertEquals(ApplicationStatus.PAYSLIP_REVIEW, recorded.status());
        assertFalse(recorded.writeDecisionRow());
        assertFalse(recorded.approves());

        DecisionResult missing = service.credit(null, open);
        assertEquals(ApplicationStatus.MANUAL_HOLD, missing.status());
        assertEquals(ReasonCode.CREDIT_SCORE_MISSING, missing.reason());

        ConfigSnapshot cutoff = snapshot(true, 1, true, 650);
        DecisionResult declined = service.credit(600, cutoff);
        assertEquals(ApplicationStatus.DECLINED, declined.status());
        assertEquals(ReasonCode.CREDIT_BELOW_CUTOFF, declined.reason());
        assertTrue(declined.writeDecisionRow());
        assertFalse(declined.approves());
    }

    @Test
    void operatorIsTheOnlyOtherApprovalAndOnlyForATerminalOutcome() {
        DecisionResult approved = service.operator(ApplicationStatus.APPROVED, "OPERATOR_POLICY", defaults());
        assertTrue(approved.approves());
        assertEquals("OPERATOR", approved.source());
        DecisionResult declined = service.operator(ApplicationStatus.DECLINED, "OPERATOR_POLICY", defaults());
        assertEquals(ApplicationStatus.DECLINED, declined.status());
        assertFalse(declined.approves());
    }

    private static DecisionFacts facts(PayslipView view) {
        return new DecisionFacts(false, 1, List.of(view), TechnicalSignal.NONE, false, 1);
    }

    private static PayslipView certain(String amount) {
        return new PayslipView(false, IdentityResult.MATCH, true, new BigDecimal(amount), null, List.of());
    }

    private static ConfigSnapshot defaults() {
        return snapshot(true, 1, false, null);
    }

    private static ConfigSnapshot snapshot(boolean autoDecision, int required, boolean cutoff, Integer minimum) {
        return ConfigSnapshot.builder()
                .generation(1)
                .valid(true)
                .salaryThreshold(new BigDecimal("7000"))
                .salaryComparison(ComparisonOperator.GREATER_THAN)
                .salaryCurrency("USD")
                .salaryAggregation("ANY_EXCEEDS")
                .conflictTolerance(new BigDecimal("1.00"))
                .sanityMax(new BigDecimal("1000000"))
                .mismatchAction("DECLINE")
                .uncertaintyAction("MANUAL_HOLD")
                .autoDecisionEnabled(autoDecision)
                .requiredPayslips(required)
                .stubScore(600)
                .creditProvider("STUB")
                .cutoffEnabled(cutoff)
                .cutoffMinimum(minimum)
                .cutoffComparison(ComparisonOperator.LESS_THAN)
                .build();
    }
}
