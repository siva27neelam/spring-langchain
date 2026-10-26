package com.loanprocessing.decision;

/**
 * Capability token. Only this package can mint a decision, so application status
 * cannot be set to APPROVED from extraction or HTTP code.
 */
final class Mint {
    private Mint() {
    }

    static Mint get() {
        return new Mint();
    }
}
