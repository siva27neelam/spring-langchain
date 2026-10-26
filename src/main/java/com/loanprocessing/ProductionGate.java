package com.loanprocessing;

/**
 * Production boot checks. Four-eyes, a real issuer, and a real scanner are not runtime knobs.
 */
final class ProductionGate {
    private ProductionGate() {
    }

    static void assertBoot(boolean production, boolean fourEyes, String securityMode, String issuer,
                            String malwareMode, String llmClient, boolean faultInjection,
                            boolean llmLogRequests, boolean scanRequired) {
        if (!production) {
            return;
        }
        if (!fourEyes) {
            throw new IllegalStateException("production refuses to start when four-eyes is disabled");
        }
        if (!"oidc".equals(securityMode) || issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("production refuses to start without an OIDC issuer");
        }
        if ("scripted".equals(malwareMode) || "scripted".equals(llmClient) || faultInjection) {
            throw new IllegalStateException("production refuses test doubles");
        }
        if (llmLogRequests || !scanRequired) {
            throw new IllegalStateException("production boot checks failed");
        }
    }
}
