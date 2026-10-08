package com.github.cuixudong74.contentmodeljava.service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Low-level helpers for interpreting raw JSON-compatible entry values.
 */
public final class ValueSupport {

    private ValueSupport() {
    }

    /** Accepts ISO-8601 instants and offset/local date-times. */
    public static boolean isIsoDateTime(String value) {
        if (value == null) {
            return false;
        }
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }
}
