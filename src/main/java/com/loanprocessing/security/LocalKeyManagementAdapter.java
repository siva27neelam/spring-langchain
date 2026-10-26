package com.loanprocessing.security;

import com.loanprocessing.support.ApiException;
import com.loanprocessing.support.FaultSwitch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Local envelope wrapper used when a cloud KMS is not available. It never returns plaintext as a fallback.
 */
@Component
public class LocalKeyManagementAdapter implements KeyManagementPort {
    private static final int VERSION = 1;
    private final byte[] master;
    private final String cmkId;
    private final FaultSwitch faults;
    private final SecureRandom random = new SecureRandom();

    public LocalKeyManagementAdapter(
            @Value("${loan.kms.local-master-key}") String masterKey,
            @Value("${loan.kms.cmk-id}") String cmkId,
            FaultSwitch faults
    ) {
        byte[] decoded = Base64.getDecoder().decode(masterKey);
        if (decoded.length != 32) {
            throw new IllegalStateException("local KMS master key must be 32 bytes");
        }
        this.master = decoded;
        this.cmkId = cmkId;
        this.faults = faults;
    }

    public String cmkId() {
        return cmkId;
    }

    @Override
    public WrappedDek wrap(byte[] dek) {
        if (faults.kmsFail()) {
            throw new ApiException(503, "KMS_UNAVAILABLE", "key service unavailable");
        }
        return new WrappedDek(encrypt(master, dek), VERSION);
    }

    @Override
    public byte[] unwrap(byte[] wrappedDek, int keyVersion) {
        if (faults.kmsFail()) {
            throw new ApiException(503, "KMS_UNAVAILABLE", "key service unavailable");
        }
        if (keyVersion != VERSION) {
            throw new ApiException(503, "KMS_UNAVAILABLE", "encryption key version is unavailable");
        }
        return decrypt(master, wrappedDek);
    }

    @Override
    public int currentKeyVersion() {
        return VERSION;
    }

    @Override
    public void selfCheck() {
        byte[] dek = new byte[32];
        random.nextBytes(dek);
        WrappedDek wrapped = wrap(dek);
        byte[] unwrapped = unwrap(wrapped.wrapped(), wrapped.keyVersion());
        if (!Arrays.equals(dek, unwrapped)) {
            throw new IllegalStateException("kms self-check failed");
        }
    }

    static byte[] encrypt(byte[] key, byte[] plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] body = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return out;
        } catch (Exception e) {
            throw new ApiException(503, "KMS_UNAVAILABLE", "encryption failed");
        }
    }

    static byte[] decrypt(byte[] key, byte[] payload) {
        try {
            byte[] iv = Arrays.copyOfRange(payload, 0, 12);
            byte[] body = Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return cipher.doFinal(body);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(503, "KMS_UNAVAILABLE", "decryption failed");
        }
    }
}
