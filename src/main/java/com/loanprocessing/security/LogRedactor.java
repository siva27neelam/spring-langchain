package com.loanprocessing.security;

import java.util.regex.Pattern;

public final class LogRedactor {
    private static final Pattern DASHED_SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern PLAIN_SSN = Pattern.compile("(?<!\\d)\\d{9}(?!\\d)");
    private static final Pattern KEYS = Pattern.compile(
            "(?i)(\"(?:ssn|dateofbirth|date_of_birth|dob|firstname|first_name|lastname|last_name|address|line1|postalcode|postal_code)\"\\s*:\\s*)(\"[^\"]*\"|\\{[^}]*\\}|\\[[^\\]]*\\])");
    private static final Pattern DOB_NEAR = Pattern.compile("(?i)(dob\\W{0,12})\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern PHONE = Pattern.compile("\\+[1-9]\\d{7,14}");

    private LogRedactor() {
    }

    public static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String out = DASHED_SSN.matcher(message).replaceAll("[redacted-ssn]");
        out = PLAIN_SSN.matcher(out).replaceAll("[redacted-ssn]");
        out = KEYS.matcher(out).replaceAll("$1\"[redacted]\"");
        out = DOB_NEAR.matcher(out).replaceAll("$1[redacted-dob]");
        out = EMAIL.matcher(out).replaceAll("$1***@$2");
        out = PHONE.matcher(out).replaceAll(match -> {
            String value = match.group();
            return "***" + value.substring(value.length() - 2);
        });
        return out;
    }
}
