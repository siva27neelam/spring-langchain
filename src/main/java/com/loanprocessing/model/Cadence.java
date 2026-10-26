package com.loanprocessing.model;

import java.util.Locale;

public enum Cadence {
    MONTHLY,
    SEMIMONTHLY,
    BIWEEKLY,
    WEEKLY,
    ANNUAL,
    UNKNOWN;

    public static Cadence parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String compact = raw.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        return switch (compact) {
            case "monthly" -> MONTHLY;
            case "semimonthly" -> SEMIMONTHLY;
            case "biweekly" -> BIWEEKLY;
            case "weekly" -> WEEKLY;
            case "annual", "annually", "yearly" -> ANNUAL;
            default -> UNKNOWN;
        };
    }
}
