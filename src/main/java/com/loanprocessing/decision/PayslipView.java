package com.loanprocessing.decision;

import com.loanprocessing.model.IdentityResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayslipView(
        boolean unreadable,
        IdentityResult identity,
        boolean salaryCertain,
        BigDecimal monthlyAmount,
        LocalDate periodEnd,
        List<String> uncertainReasons
) {
    public PayslipView {
        uncertainReasons = uncertainReasons == null ? List.of() : List.copyOf(uncertainReasons);
    }
}
