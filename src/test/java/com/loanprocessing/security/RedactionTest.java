package com.loanprocessing.security;

import com.loanprocessing.document.FailClosedMalwareScanAdapter;
import com.loanprocessing.support.ApiException;
import com.loanprocessing.support.Synthetic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionTest {
    @Test
    void documentRedactionDropsAFullNumberBeforeTheModelSeesIt() {
        String dashed = Synthetic.dashed(7);
        String digits = dashed.replace("-", "");
        String text = "employee " + dashed + " and " + digits + " end";
        String redacted = PiiRedactor.redactDocument(text);
        assertFalse(redacted.contains(dashed));
        assertFalse(redacted.contains(digits));
        assertTrue(redacted.contains("***-**-" + digits.substring(5)));
    }

    @Test
    void logRedactionDropsDashedNumbersEmailsAndDenylistKeys() {
        String dashed = Synthetic.illegalDashed("000", "00", "0000");
        String message = "ssn=" + dashed + " {\"ssn\":\"" + dashed + "\",\"firstName\":\"Test\"} a@example.test +12025550199";
        String redacted = LogRedactor.redact(message);
        assertFalse(redacted.contains(dashed));
        assertTrue(redacted.contains("[redacted-ssn]"));
        assertTrue(redacted.contains("[redacted]"));
        assertFalse(redacted.contains("a@example.test"));
        assertFalse(redacted.contains("+12025550199"));
    }

    @Test
    void failClosedScannerNeverReturnsClean() {
        FailClosedMalwareScanAdapter adapter = new FailClosedMalwareScanAdapter();
        ApiException error = assertThrows(ApiException.class, () -> adapter.scan(new byte[]{1, 2, 3}, "application/pdf"));
        assertEquals(503, error.status());
        assertEquals("SCANNER_UNAVAILABLE", error.reasonCode());
    }
}
