package com.JobHunt.appJob.source;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Small null-safe JSON helpers shared by the newer sources. */
final class Json {

    private Json() {
    }

    /** Text value, or {@code null} for missing/null/blank nodes. */
    static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.asString();
        return s == null || s.isBlank() ? null : s;
    }

    /** Number value, or {@code null}. */
    static Double number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asDouble();
    }

    /**
     * Parses ISO timestamps with or without an offset ("2026-11-24T05:39:55.574Z",
     * "2026-11-19T14:58:42" → treated as UTC). Returns {@code null} when unparseable.
     */
    static Instant instant(String s) {
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
