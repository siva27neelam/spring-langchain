package com.loanprocessing.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SecretProvider {
    public static final String LLM = "SECRET_LLM_API_KEY";
    public static final String HMAC = "SECRET_SSN_HMAC_KEY";
    public static final String EXPERIAN_ID = "SECRET_EXPERIAN_CLIENT_ID";
    public static final String EXPERIAN_SECRET = "SECRET_EXPERIAN_CLIENT_SECRET";

    private final byte[] hmacKey;
    private final String llmApiKey;
    private final ConcurrentHashMap<String, AtomicInteger> reads = new ConcurrentHashMap<>();

    public SecretProvider(
            @Value("${loan.secrets.ssn-hmac-key}") String hmacKey,
            @Value("${SECRET_LLM_API_KEY:}") String llmApiKey
    ) {
        this.hmacKey = Base64.getDecoder().decode(hmacKey);
        if (this.hmacKey.length < 16) {
            throw new IllegalStateException("SSN HMAC key is missing");
        }
        this.llmApiKey = llmApiKey == null ? "" : llmApiKey;
    }

    public byte[] ssnHmacKey() {
        mark(HMAC);
        return hmacKey.clone();
    }

    public String llmApiKey() {
        mark(LLM);
        return llmApiKey;
    }

    public String versionId(String name) {
        return name + ":local";
    }

    public int reads(String name) {
        return reads.getOrDefault(name, new AtomicInteger()).get();
    }

    public void resetReads() {
        reads.clear();
    }

    private void mark(String name) {
        if (EXPERIAN_ID.equals(name) || EXPERIAN_SECRET.equals(name)) {
            throw new IllegalStateException("Experian secrets are not read while credit.provider=STUB");
        }
        reads.computeIfAbsent(name, key -> new AtomicInteger()).incrementAndGet();
    }
}
