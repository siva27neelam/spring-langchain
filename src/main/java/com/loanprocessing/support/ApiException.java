package com.loanprocessing.support;

public class ApiException extends RuntimeException {
    private final int status;
    private final String reasonCode;

    public ApiException(int status, String reasonCode, String detail) {
        super(detail);
        this.status = status;
        this.reasonCode = reasonCode;
    }

    public int status() {
        return status;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
