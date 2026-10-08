package com.github.cuixudong74.contentmodeljava.web.dto;

/**
 * A projected field value as seen by a reader.
 *
 * @param state    PRESENT | DEFAULTED | UNSET
 * @param value    resolved value; null when UNSET
 * @param locale   locale actually served for localized fields; null for non-localized fields
 * @param fallback true when a localized value was served from a fallback locale
 */
public record FieldValueView(String state, Object value, String locale, boolean fallback) {

    public static FieldValueView present(Object value) {
        return new FieldValueView("PRESENT", value, null, false);
    }

    public static FieldValueView defaulted(Object value) {
        return new FieldValueView("DEFAULTED", value, null, false);
    }

    public static FieldValueView unset() {
        return new FieldValueView("UNSET", null, null, false);
    }
}
