package com.loanprocessing.extraction;

import com.loanprocessing.support.Synthetic;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PayslipPdfFixtureTest {
    @Test
    void generatedPayslipTextKeepsTheLabelsTheEchoModelGrounds() throws Exception {
        byte[] pdf = Synthetic.payslip(Synthetic.payslipLines("Applicant", "6500.00"));
        String text = new ApachePdfBoxDocumentParser().parse(new ByteArrayInputStream(pdf)).text();
        String folded = text.toLowerCase();
        assertTrue(folded.contains("test applicant"), text);
        assertTrue(text.contains("6500.00"), text);
        assertTrue(folded.contains("monthly"), text);
        assertTrue(folded.contains("10 test street"), text);
        assertTrue(PayslipExtractionPipeline.amountAppears(new java.math.BigDecimal("6500.00"), text));
    }
}
