package com.loanprocessing.matching;

import com.loanprocessing.model.IdentityResult;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic identity compare. The model is not asked whether the person matches.
 * A grounded clear difference is MISMATCH. A one-character last-name typo or a nickname is UNCERTAIN.
 */
public final class IdentityMatcher {

    public Match match(Subject subject, Extracted extracted, BigDecimal overlapFloor) {
        Map<String, IdentityResult> detail = new HashMap<>();
        IdentityResult name = name(subject, extracted, detail);
        IdentityResult address = address(subject, extracted, overlapFloor, detail);
        IdentityResult ssn = ssn(subject, extracted, detail);
        IdentityResult dob = dob(subject, extracted, detail);
        boolean presentButUngrounded = (extracted.ssnPresent() && !extracted.ssnGrounded())
                || (extracted.dobPresent() && !extracted.dobGrounded());
        IdentityResult overall;
        if (name == IdentityResult.MISMATCH || address == IdentityResult.MISMATCH
                || ssn == IdentityResult.MISMATCH || dob == IdentityResult.MISMATCH) {
            overall = IdentityResult.MISMATCH;
        } else if (presentButUngrounded) {
            overall = IdentityResult.UNCERTAIN;
        } else if (name == IdentityResult.MATCH && address == IdentityResult.MATCH) {
            overall = IdentityResult.MATCH;
        } else {
            overall = IdentityResult.UNCERTAIN;
        }
        return new Match(overall, Map.copyOf(detail));
    }

    private static IdentityResult name(Subject subject, Extracted extracted, Map<String, IdentityResult> detail) {
        if (!extracted.lastNameGrounded()) {
            detail.put("lastName", IdentityResult.UNCERTAIN);
            detail.put("firstName", IdentityResult.UNCERTAIN);
            return IdentityResult.UNCERTAIN;
        }
        IdentityResult last = compareLast(subject.lastName(), extracted.lastName());
        detail.put("lastName", last);
        if (!extracted.firstNameGrounded()) {
            detail.put("firstName", IdentityResult.UNCERTAIN);
            return last == IdentityResult.MISMATCH ? IdentityResult.MISMATCH : IdentityResult.UNCERTAIN;
        }
        String appFirst = normalize(subject.firstName());
        String docFirst = normalize(extracted.firstName());
        IdentityResult first;
        if (appFirst.equals(docFirst)) {
            first = IdentityResult.MATCH;
        } else {
            first = IdentityResult.UNCERTAIN;
        }
        detail.put("firstName", first);
        if (last == IdentityResult.MISMATCH) {
            return IdentityResult.MISMATCH;
        }
        if (last == IdentityResult.MATCH && first == IdentityResult.MATCH) {
            return IdentityResult.MATCH;
        }
        return IdentityResult.UNCERTAIN;
    }

    private static IdentityResult compareLast(String application, String document) {
        String left = normalize(application);
        String right = normalize(document);
        if (left.isEmpty() || right.isEmpty()) {
            return IdentityResult.UNCERTAIN;
        }
        int distance = levenshtein(left, right);
        if (distance == 0) {
            return IdentityResult.MATCH;
        }
        if (distance == 1) {
            return IdentityResult.UNCERTAIN;
        }
        return IdentityResult.MISMATCH;
    }

    private static IdentityResult address(Subject subject, Extracted extracted, BigDecimal overlapFloor, Map<String, IdentityResult> detail) {
        String appPostal = digits(subject.postalCode());
        String docPostal = extracted.postalGrounded() ? digits(extracted.postalCode()) : "";
        String appCity = normalize(subject.city());
        String docCity = extracted.cityGrounded() ? normalize(extracted.city()) : "";
        if (!docPostal.isEmpty() && !appPostal.isEmpty() && !postalEqual(appPostal, docPostal)) {
            detail.put("postalCode", IdentityResult.MISMATCH);
            detail.put("address", IdentityResult.MISMATCH);
            return IdentityResult.MISMATCH;
        }
        if (!docCity.isEmpty() && !appCity.isEmpty() && !appCity.equals(docCity)) {
            detail.put("city", IdentityResult.MISMATCH);
            detail.put("address", IdentityResult.MISMATCH);
            return IdentityResult.MISMATCH;
        }
        if (!extracted.line1Grounded() || !extracted.postalGrounded() || !extracted.cityGrounded()) {
            detail.put("address", IdentityResult.UNCERTAIN);
            return IdentityResult.UNCERTAIN;
        }
        if (!postalEqual(appPostal, docPostal) || !appCity.equals(docCity)) {
            detail.put("address", IdentityResult.UNCERTAIN);
            return IdentityResult.UNCERTAIN;
        }
        double overlap = tokenOverlap(subject.line1(), extracted.line1());
        double floor = overlapFloor == null ? 0.80d : overlapFloor.doubleValue();
        IdentityResult line = overlap + 1e-9 >= floor ? IdentityResult.MATCH : IdentityResult.UNCERTAIN;
        detail.put("line1", line);
        detail.put("address", line);
        return line;
    }

    private static boolean postalEqual(String left, String right) {
        if (left.equals(right)) {
            return true;
        }
        String a = left.length() >= 5 ? left.substring(0, 5) : left;
        String b = right.length() >= 5 ? right.substring(0, 5) : right;
        return a.length() == 5 && a.equals(b);
    }

    private static IdentityResult ssn(Subject subject, Extracted extracted, Map<String, IdentityResult> detail) {
        if (!extracted.ssnPresent()) {
            return IdentityResult.MATCH;
        }
        if (!extracted.ssnGrounded()) {
            detail.put("ssnLast4", IdentityResult.UNCERTAIN);
            return IdentityResult.UNCERTAIN;
        }
        IdentityResult result = extracted.ssnLast4().equals(subject.ssnLast4())
                ? IdentityResult.MATCH
                : IdentityResult.MISMATCH;
        detail.put("ssnLast4", result);
        return result;
    }

    private static IdentityResult dob(Subject subject, Extracted extracted, Map<String, IdentityResult> detail) {
        if (!extracted.dobPresent()) {
            return IdentityResult.MATCH;
        }
        if (!extracted.dobGrounded()) {
            detail.put("dateOfBirth", IdentityResult.UNCERTAIN);
            return IdentityResult.UNCERTAIN;
        }
        IdentityResult result = extracted.dateOfBirth().equals(subject.dateOfBirth())
                ? IdentityResult.MATCH
                : IdentityResult.MISMATCH;
        detail.put("dateOfBirth", result);
        return result;
    }

    public static double tokenOverlap(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return intersection.size() / (double) Math.max(a.size(), b.size());
    }

    private static Set<String> tokens(String value) {
        Set<String> out = new HashSet<>();
        for (String token : normalize(value).split(" ")) {
            if (!token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return folded.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String digits(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\D", "");
    }

    static int levenshtein(String left, String right) {
        int[] prev = new int[right.length() + 1];
        int[] curr = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[right.length()];
    }

    public record Subject(
            String firstName,
            String lastName,
            String line1,
            String city,
            String postalCode,
            String ssnLast4,
            LocalDate dateOfBirth
    ) {
    }

    public record Extracted(
            String firstName,
            boolean firstNameGrounded,
            String lastName,
            boolean lastNameGrounded,
            String line1,
            boolean line1Grounded,
            String city,
            boolean cityGrounded,
            String postalCode,
            boolean postalGrounded,
            boolean ssnPresent,
            boolean ssnGrounded,
            String ssnLast4,
            boolean dobPresent,
            boolean dobGrounded,
            LocalDate dateOfBirth
    ) {
    }

    public record Match(IdentityResult result, Map<String, IdentityResult> detail) {
    }
}
