package com.loanprocessing.decision;

import com.loanprocessing.model.Cadence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalaryNormalizerTest {
    private static final BigDecimal SANITY = new BigDecimal("1000000");

    @Test
    void javaNormalizesCadenceAndDoesNotLetTheModelDoTheMath() {
        assertEquals(new BigDecimal("2000.00"), SalaryNormalizer.normalize(new BigDecimal("1000"), Cadence.SEMIMONTHLY, SANITY).monthly());
        assertEquals(new BigDecimal("2600.00"), SalaryNormalizer.normalize(new BigDecimal("1200"), Cadence.BIWEEKLY, SANITY).monthly());
        assertEquals(new BigDecimal("1300.00"), SalaryNormalizer.normalize(new BigDecimal("300"), Cadence.WEEKLY, SANITY).monthly());
        assertEquals(new BigDecimal("1000.00"), SalaryNormalizer.normalize(new BigDecimal("12000"), Cadence.ANNUAL, SANITY).monthly());
        assertEquals(new BigDecimal("6500.00"), SalaryNormalizer.normalize(new BigDecimal("6500"), Cadence.MONTHLY, SANITY).monthly());
    }

    @Test
    void unknownCadenceAndAboveSanityAreUncertain() {
        SalaryNormalizer.Result unknown = SalaryNormalizer.normalize(new BigDecimal("1000"), Cadence.UNKNOWN, SANITY);
        assertFalse(unknown.certain());
        assertEquals("UNKNOWN_CADENCE", unknown.reason());
        SalaryNormalizer.Result huge = SalaryNormalizer.normalize(new BigDecimal("2000000"), Cadence.MONTHLY, SANITY);
        assertFalse(huge.certain());
        assertEquals("ABOVE_SANITY_MAX", huge.reason());
        assertTrue(SalaryNormalizer.normalize(new BigDecimal("1"), Cadence.MONTHLY, SANITY).certain());
    }
}
