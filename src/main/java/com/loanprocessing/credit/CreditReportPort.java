package com.loanprocessing.credit;

import com.loanprocessing.model.ConfigSnapshot;

import java.util.UUID;

public interface CreditReportPort {
    Pull pull(UUID applicationId, String providerRequestId, ConfigSnapshot snapshot);

    record Pull(int score, String provider, String scoreScale) {
    }
}
