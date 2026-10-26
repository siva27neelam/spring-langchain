package com.loanprocessing.model;

public enum TechnicalSignal {
    NONE,
    RETRYABLE,
    BUDGET_EXHAUSTED,
    CIRCUIT_OPEN,
    CIRCUIT_HOLD,
    SCHEMA_INVALID,
    POISON,
    MODEL_NOT_CONFIGURED,
    DECRYPT_FAILED
}
