package com.loanprocessing.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Arrays;

public class FieldEncryptor {
    private final KeyManagementPort kms;
    private final SecretProvider secrets;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(KeyManagementPort kms, SecretProvider secrets, ObjectMapper mapper) {
        this.kms = kms;
        this.secrets = secrets;
        this.mapper = mapper;
    }

    public Sealed seal(String firstName, String lastName, String ssnDigits, LocalDate dob, String email, String phone, ObjectNode address) {
        try {
            byte[] dek = new byte[32];
            random.nextBytes(dek);
            KeyManagementPort.WrappedDek wrapped = kms.wrap(dek);
            String addressJson = mapper.writeValueAsString(address);
            return new Sealed(
                    wrapped.wrapped(),
                    wrapped.keyVersion(),
                    LocalKeyManagementAdapter.encrypt(dek, firstName.getBytes(StandardCharsets.UTF_8)),
                    LocalKeyManagementAdapter.encrypt(dek, lastName.getBytes(StandardCharsets.UTF_8)),
                    LocalKeyManagementAdapter.encrypt(dek, ssnDigits.getBytes(StandardCharsets.UTF_8)),
                    ssnDigits.substring(5),
                    hmac(ssnDigits),
                    LocalKeyManagementAdapter.encrypt(dek, dob.toString().getBytes(StandardCharsets.UTF_8)),
                    LocalKeyManagementAdapter.encrypt(dek, email.getBytes(StandardCharsets.UTF_8)),
                    LocalKeyManagementAdapter.encrypt(dek, phone.getBytes(StandardCharsets.UTF_8)),
                    LocalKeyManagementAdapter.encrypt(dek, addressJson.getBytes(StandardCharsets.UTF_8)),
                    dek
            );
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new com.loanprocessing.support.ApiException(503, "KMS_UNAVAILABLE", "encryption failed");
        }
    }

    public Open open(byte[] wrappedDek, int version, byte[] first, byte[] last, byte[] ssn, byte[] dob, byte[] email, byte[] phone, byte[] address) {
        byte[] dek = kms.unwrap(wrappedDek, version);
        try {
            return new Open(
                    text(dek, first),
                    text(dek, last),
                    text(dek, ssn),
                    LocalDate.parse(text(dek, dob)),
                    text(dek, email),
                    text(dek, phone),
                    text(dek, address)
            );
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public byte[] encryptWith(byte[] dek, String plaintext) {
        return LocalKeyManagementAdapter.encrypt(dek, plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] hmac(String ssnDigits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secrets.ssnHmacKey(), "HmacSHA256"));
            return mac.doFinal(ssnDigits.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failed");
        }
    }

    private static String text(byte[] dek, byte[] payload) {
        return new String(LocalKeyManagementAdapter.decrypt(dek, payload), StandardCharsets.UTF_8);
    }

    public record Sealed(
            byte[] wrappedDek,
            int keyVersion,
            byte[] firstName,
            byte[] lastName,
            byte[] ssn,
            String ssnLast4,
            byte[] ssnHmac,
            byte[] dob,
            byte[] email,
            byte[] phone,
            byte[] address,
            byte[] dek
    ) {
    }

    public record Open(
            String firstName,
            String lastName,
            String ssnDigits,
            LocalDate dateOfBirth,
            String email,
            String phone,
            String addressJson
    ) {
    }
}
