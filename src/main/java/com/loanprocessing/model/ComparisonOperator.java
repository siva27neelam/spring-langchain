package com.loanprocessing.model;

import java.math.BigDecimal;

public enum ComparisonOperator {
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL;

    public boolean fires(BigDecimal amount, BigDecimal threshold) {
        int cmp = amount.compareTo(threshold);
        return switch (this) {
            case GREATER_THAN -> cmp > 0;
            case GREATER_THAN_OR_EQUAL -> cmp >= 0;
            case LESS_THAN -> cmp < 0;
            case LESS_THAN_OR_EQUAL -> cmp <= 0;
        };
    }

    public boolean fires(int score, int minimum) {
        return fires(BigDecimal.valueOf(score), BigDecimal.valueOf(minimum));
    }
}
