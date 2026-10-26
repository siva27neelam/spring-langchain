package com.loanprocessing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionGateTest {
    @Test
    void productionRefusesTestDoublesAndAMissingIssuer() {
        assertThrows(IllegalStateException.class, () -> ProductionGate.assertBoot(
                true, false, "oidc", "https://issuer.example.test", "fail-closed", "openai", false, false, true));
        assertThrows(IllegalStateException.class, () -> ProductionGate.assertBoot(
                true, true, "header", "", "fail-closed", "openai", false, false, true));
        assertThrows(IllegalStateException.class, () -> ProductionGate.assertBoot(
                true, true, "oidc", "https://issuer.example.test", "scripted", "openai", false, false, true));
        assertThrows(IllegalStateException.class, () -> ProductionGate.assertBoot(
                true, true, "oidc", "https://issuer.example.test", "fail-closed", "scripted", false, false, true));
        assertThrows(IllegalStateException.class, () -> ProductionGate.assertBoot(
                true, true, "oidc", "https://issuer.example.test", "fail-closed", "openai", true, false, true));
        assertThrows(IllegalStateException.class, () -> ProductionGate.assertBoot(
                true, true, "oidc", "https://issuer.example.test", "fail-closed", "openai", false, true, true));
        assertThrows(IllegalStateException.class, () -> ProductionGate.assertBoot(
                true, true, "oidc", "https://issuer.example.test", "fail-closed", "openai", false, false, false));
        assertDoesNotThrow(() -> ProductionGate.assertBoot(
                false, false, "header", "", "scripted", "scripted", true, true, false));
    }
}
