package com.loanprocessing.matching;

import com.loanprocessing.model.IdentityResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdentityMatcherTest {
    private final IdentityMatcher matcher = new IdentityMatcher();
    private final BigDecimal floor = new BigDecimal("0.80");

    @Test
    void exactGroundedIdentityMatchesAndAClearLastNameDifferenceDoesNot() {
        assertEquals(IdentityResult.MATCH, matcher.match(subject("Applicant"), extracted("Applicant", true), floor).result());
        assertEquals(IdentityResult.MISMATCH, matcher.match(subject("Applicant"), extracted("Otherperson", true), floor).result());
    }

    @Test
    void oneCharacterLastNameOrDifferentFirstNameIsUncertainNotAMismatch() {
        assertEquals(IdentityResult.UNCERTAIN, matcher.match(subject("Applicant"), extracted("Applicent", true), floor).result());
        IdentityMatcher.Extracted nickname = extracted("Applicant", true);
        nickname = new IdentityMatcher.Extracted("Tester", true, "Applicant", true, "10 Test Street", true,
                "Testville", true, "94105", true, false, false, null, false, false, null);
        assertEquals(IdentityResult.UNCERTAIN, matcher.match(subject("Applicant"), nickname, floor).result());
    }

    @Test
    void absentSsnAndDobAreIgnoredAndAPresentMismatchIsTerminal() {
        assertEquals(IdentityResult.MATCH, matcher.match(subject("Applicant"), extracted("Applicant", true), floor).result());
        IdentityMatcher.Extracted wrongSsn = new IdentityMatcher.Extracted("Test", true, "Applicant", true, "10 Test Street", true,
                "Testville", true, "94105", true, true, true, "9999", false, false, null);
        assertEquals(IdentityResult.MISMATCH, matcher.match(subject("Applicant"), wrongSsn, floor).result());
        IdentityMatcher.Extracted ungroundedSsn = new IdentityMatcher.Extracted("Test", true, "Applicant", true, "10 Test Street", true,
                "Testville", true, "94105", true, true, false, "1000", false, false, null);
        assertEquals(IdentityResult.UNCERTAIN, matcher.match(subject("Applicant"), ungroundedSsn, floor).result());
    }

    private static IdentityMatcher.Subject subject(String lastName) {
        return new IdentityMatcher.Subject("Test", lastName, "10 Test Street", "Testville", "94105", "1000",
                LocalDate.of(1990, 1, 1));
    }

    private static IdentityMatcher.Extracted extracted(String lastName, boolean grounded) {
        return new IdentityMatcher.Extracted("Test", grounded, lastName, grounded, "10 Test Street", true,
                "Testville", true, "94105", true, false, false, null, false, false, null);
    }
}
