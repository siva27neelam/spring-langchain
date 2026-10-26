package com.loanprocessing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanprocessing.model.ConfigSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatorTest {
    private final ConfigValidator validator = new ConfigValidator(new ObjectMapper(), false, false);

    @Test
    void seedDefaultsMatchTheConfirmedProductRules() {
        ConfigSnapshot snapshot = validator.parse(1, ConfigCatalog.defaults());
        assertTrue(snapshot.valid(), snapshot.invalidReason());
        assertEquals(0, snapshot.salaryThreshold().compareTo(new java.math.BigDecimal("7000")));
        assertEquals("GREATER_THAN", snapshot.salaryComparison().name());
        assertEquals(600, snapshot.stubScore());
        assertFalse(snapshot.cutoffEnabled());
        assertEquals(1, snapshot.requiredPayslips());
        assertEquals("STUB", snapshot.creditProvider());
        assertEquals(java.time.Period.ofDays(14), snapshot.abandonAfter());
        assertFalse(snapshot.modelConfigured());
    }

    @Test
    void uncertaintyCannotBeConfiguredToApproveAndExperianIsRejected() {
        assertFalse(changed("decision.uncertainty.action", "\"APPROVED\"").valid());
        assertFalse(changed("decision.identity.mismatch-action", "\"APPROVED\"").valid());
        assertFalse(changed("credit.provider", "\"EXPERIAN\"").valid());
        assertFalse(changed("llm.temperature", "1").valid());
        assertFalse(changed("documents.ocr.enabled", "true").valid());
        assertTrue(changed("decision.uncertainty.action", "\"DECLINE\"").valid());
    }

    @Test
    void productionRejectsAScannerThatIsNotRequired() {
        ConfigValidator production = new ConfigValidator(new ObjectMapper(), true, false);
        Map<String, String> raw = new LinkedHashMap<>(ConfigCatalog.defaults());
        raw.put("documents.scan.required", "false");
        assertFalse(production.parse(1, raw).valid());
        raw.put("documents.scan.required", "true");
        raw.put("llm.log-requests", "true");
        assertFalse(production.parse(1, raw).valid());
    }

    private ConfigSnapshot changed(String key, String json) {
        Map<String, String> raw = new LinkedHashMap<>(ConfigCatalog.defaults());
        raw.put(key, json);
        return validator.parse(1, raw);
    }
}
