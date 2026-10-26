package com.loanprocessing.decision;

import com.loanprocessing.model.TechnicalSignal;

import java.util.List;

public record DecisionFacts(
        boolean withdrawRequested,
        int requiredCount,
        List<PayslipView> payslips,
        TechnicalSignal technical,
        boolean attemptsRemain,
        long configGeneration
) {
    public DecisionFacts {
        payslips = payslips == null ? List.of() : List.copyOf(payslips);
        if (technical == null) {
            technical = TechnicalSignal.NONE;
        }
    }
}
