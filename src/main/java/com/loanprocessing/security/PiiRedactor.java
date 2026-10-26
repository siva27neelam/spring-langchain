package com.loanprocessing.security;

import java.util.regex.Pattern;

public final class PiiRedactor {
    private static final Pattern DASHED_SSN = Pattern.compile("\\b(\\d{3})-(\\d{2})-(\\d{4})\\b");
    private static final Pattern PLAIN_SSN = Pattern.compile("(?<!\\d)(\\d{3})(\\d{2})(\\d{4})(?!\\d)");

    private PiiRedactor() {
    }

    public static String redactDocument(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String dashed = DASHED_SSN.matcher(text).replaceAll("***-**-$3");
        return PLAIN_SSN.matcher(dashed).replaceAll("***-**-$3");
    }
}
