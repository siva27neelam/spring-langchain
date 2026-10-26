package com.loanprocessing.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Synthetic fixtures only. Numbers are built so the source never contains a real or sample SSN.
 */
public final class Synthetic {
    private Synthetic() {
    }

    public static String digits(int n) {
        int area = 200 + Math.floorMod(n, 400);
        if (area == 666) {
            area = 211;
        }
        int group = 11 + Math.floorMod(n, 70);
        int serial = 1000 + Math.floorMod(n, 8000);
        return String.format("%03d%02d%04d", area, group, serial);
    }

    public static String dashed(int n) {
        String digits = digits(n);
        return digits.substring(0, 3) + "-" + digits.substring(3, 5) + "-" + digits.substring(5);
    }

    public static String illegalDashed(String area, String group, String serial) {
        return area + "-" + group + "-" + serial;
    }

    public static LocalDate adultBirthDate() {
        return LocalDate.now(ZoneOffset.UTC).minusYears(30);
    }

    public static byte[] payslip(String... lines) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.setLeading(16);
                stream.newLineAtOffset(50, 720);
                for (String line : lines) {
                    stream.showText(line);
                    stream.newLine();
                }
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    public static String[] payslipLines(String lastName, String gross) {
        return new String[]{
                "Test " + lastName,
                "firstName: Test",
                "lastName: " + lastName,
                "line1: 10 Test Street",
                "city: Testville",
                "region: CA",
                "postalCode: 94105",
                "gross: " + gross,
                "cadence: MONTHLY",
                "currency: USD",
                "documentKind: PAYSLIP",
                "employer: Test Employer"
        };
    }
}
