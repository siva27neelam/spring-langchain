package com.loanprocessing.security;

public interface KeyManagementPort {
    WrappedDek wrap(byte[] dek);

    byte[] unwrap(byte[] wrappedDek, int keyVersion);

    int currentKeyVersion();

    void selfCheck();

    record WrappedDek(byte[] wrapped, int keyVersion) {
    }
}
