package com.loanprocessing.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Hashes {
    private Hashes() {
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonicalJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            Map<String, String> fields = new TreeMap<>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), canonicalJson(entry.getValue())));
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append('"').append(escape(entry.getKey())).append('"').append(':').append(entry.getValue());
            }
            return builder.append('}').toString();
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(child -> items.add(canonicalJson(child)));
            return "[" + String.join(",", items) + "]";
        }
        return node.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
