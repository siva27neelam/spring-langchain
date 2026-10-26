package com.loanprocessing.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanprocessing.model.ConfigSnapshot;
import com.loanprocessing.support.ApiException;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

public final class IntakeValidator {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern ZIP = Pattern.compile("^\\d{5}(-\\d{4})?$");

    private IntakeValidator() {
    }

    public static Submission parse(JsonNode body, ConfigSnapshot snapshot) {
        String first = text(body, "firstName", 100);
        String last = text(body, "lastName", 100);
        String ssn = ssn(text(body, "ssn", 11));
        LocalDate dob = dob(text(body, "dateOfBirth", 10), snapshot.minAgeYears());
        String email = text(body, "email", 200);
        if (!EMAIL.matcher(email).matches() || email.chars().filter(ch -> ch == '@').count() != 1) {
            throw new ApiException(422, "VALIDATION", "email is invalid");
        }
        String phone = text(body, "phone", 20);
        if (!PHONE.matcher(phone).matches()) {
            throw new ApiException(422, "VALIDATION", "phone is invalid");
        }
        JsonNode address = body.get("address");
        if (address == null || !address.isObject()) {
            throw new ApiException(422, "VALIDATION", "address is invalid");
        }
        String line1 = text(address, "line1", 200);
        String line2 = address.hasNonNull("line2") ? text(address, "line2", 200) : "";
        String city = text(address, "city", 100);
        String region = text(address, "region", 2);
        if (!region.matches("^[A-Z]{2}$")) {
            throw new ApiException(422, "VALIDATION", "address is invalid");
        }
        String postal = text(address, "postalCode", 10);
        if (!ZIP.matcher(postal).matches()) {
            throw new ApiException(422, "VALIDATION", "address is invalid");
        }
        String country = text(address, "country", 2);
        if (!country.equals(snapshot.allowedCountry())) {
            throw new ApiException(422, "COUNTRY_NOT_ALLOWED", "country is not supported");
        }
        return new Submission(first, last, ssn, dob, email, phone, line1, line2, city, region, postal, country);
    }

    private static String ssn(String raw) {
        String digits;
        if (raw.matches("\\d{3}-\\d{2}-\\d{4}")) {
            digits = raw.replace("-", "");
        } else if (raw.matches("\\d{9}")) {
            digits = raw;
        } else {
            throw new ApiException(422, "VALIDATION", "ssn is invalid");
        }
        int area = Integer.parseInt(digits.substring(0, 3));
        int group = Integer.parseInt(digits.substring(3, 5));
        int serial = Integer.parseInt(digits.substring(5, 9));
        if (area == 0 || area == 666 || area >= 900 || group == 0 || serial == 0) {
            throw new ApiException(422, "VALIDATION", "ssn is invalid");
        }
        return digits;
    }

    private static LocalDate dob(String raw, int minAge) {
        LocalDate date;
        try {
            date = LocalDate.parse(raw);
        } catch (Exception e) {
            throw new ApiException(422, "VALIDATION", "dateOfBirth is invalid");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (date.isAfter(today)) {
            throw new ApiException(422, "VALIDATION", "dateOfBirth is invalid");
        }
        if (Period.between(date, today).getYears() < minAge) {
            throw new ApiException(422, "BELOW_MIN_AGE", "applicant does not meet the minimum age");
        }
        return date;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > max) {
            if ("ssn".equals(field)) {
                throw new ApiException(422, "VALIDATION", "ssn is invalid");
            }
            if ("dateOfBirth".equals(field)) {
                throw new ApiException(422, "VALIDATION", "dateOfBirth is invalid");
            }
            throw new ApiException(422, "VALIDATION", field + " is invalid");
        }
        return value.asText().trim();
    }

    public record Submission(
            String firstName,
            String lastName,
            String ssnDigits,
            LocalDate dateOfBirth,
            String email,
            String phone,
            String line1,
            String line2,
            String city,
            String region,
            String postalCode,
            String country
    ) {
    }
}
