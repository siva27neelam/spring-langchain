package com.loanprocessing.decision;

import com.loanprocessing.model.Cadence;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Monthly salary is computed here. The model does not do the multiplication that feeds the rule.
 */
public final class SalaryNormalizer {
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final BigDecimal TWENTY_SIX = new BigDecimal("26");
    private static final BigDecimal FIFTY_TWO = new BigDecimal("52");

    private SalaryNormalizer() {
    }

    public static Result normalize(BigDecimal gross, Cadence cadence, BigDecimal sanityMax) {
        if (gross == null || cadence == null || cadence == Cadence.UNKNOWN) {
            return Result.uncertain("UNKNOWN_CADENCE");
        }
        if (gross.signum() <= 0) {
            return Result.uncertain("NON_POSITIVE_AMOUNT");
        }
        BigDecimal monthly = switch (cadence) {
            case MONTHLY -> gross;
            case SEMIMONTHLY -> gross.multiply(TWO);
            case BIWEEKLY -> gross.multiply(TWENTY_SIX).divide(TWELVE, 8, RoundingMode.HALF_EVEN);
            case WEEKLY -> gross.multiply(FIFTY_TWO).divide(TWELVE, 8, RoundingMode.HALF_EVEN);
            case ANNUAL -> gross.divide(TWELVE, 8, RoundingMode.HALF_EVEN);
            case UNKNOWN -> null;
        };
        if (monthly == null) {
            return Result.uncertain("UNKNOWN_CADENCE");
        }
        monthly = monthly.setScale(2, RoundingMode.HALF_EVEN);
        if (sanityMax != null && monthly.compareTo(sanityMax) > 0) {
            return Result.uncertain("ABOVE_SANITY_MAX");
        }
        return Result.certain(monthly);
    }

    public record Result(boolean certain, BigDecimal monthly, String reason) {
        static Result certain(BigDecimal monthly) {
            return new Result(true, monthly, null);
        }

        static Result uncertain(String reason) {
            return new Result(false, null, reason);
        }
    }
}
