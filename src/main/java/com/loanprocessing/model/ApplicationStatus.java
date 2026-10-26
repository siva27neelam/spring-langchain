package com.loanprocessing.model;

public enum ApplicationStatus {
    RECEIVED,
    CREDIT_IN_PROGRESS,
    CREDIT_FAILED,
    PAYSLIP_REVIEW,
    EXTRACTION_QUEUED,
    EXTRACTING,
    EXTRACTION_FAILED,
    MANUAL_HOLD,
    APPROVED,
    DECLINED,
    ABANDONED,
    WITHDRAWN;

    public boolean terminal() {
        return this == APPROVED || this == DECLINED || this == ABANDONED || this == WITHDRAWN;
    }

    public boolean acceptingPayslips() {
        return this == PAYSLIP_REVIEW;
    }
}
