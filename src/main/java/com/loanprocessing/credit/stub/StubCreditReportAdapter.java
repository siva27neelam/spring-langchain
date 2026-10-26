package com.loanprocessing.credit.stub;

import com.loanprocessing.credit.CreditReportPort;
import com.loanprocessing.model.ConfigSnapshot;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Returns the configured stub score. This adapter has no HTTP client and does not read identity material.
 */
@Component
public class StubCreditReportAdapter implements CreditReportPort {
    @Override
    public Pull pull(UUID applicationId, String providerRequestId, ConfigSnapshot snapshot) {
        if (snapshot == null || !snapshot.valid() || !"STUB".equals(snapshot.creditProvider())) {
            throw new IllegalStateException("stub credit adapter requires a valid STUB snapshot");
        }
        return new Pull(snapshot.stubScore(), "STUB", "STUB_SCORE");
    }
}
