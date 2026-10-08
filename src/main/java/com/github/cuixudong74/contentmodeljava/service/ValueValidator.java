package com.github.cuixudong74.contentmodeljava.service;

import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.domain.FieldType;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates raw entry values against field definitions. Shape checks verify JSON types and
 * per-type constraints; reference target existence is validated separately by
 * {@link ReferenceIntegrityService} because it needs store access.
 */
public final class ValueValidator {

    private ValueValidator() {
    }

    /** True when the stored value has the right JSON shape for the field and satisfies constraints. */
    public static boolean isShapeValid(FieldDefinition field, Object value) {
        if (value == null) {
            return false;
        }
        if (field.type() == FieldType.STRING) {
            return field.localized() ? value instanceof Map : isStringInRange(field, value);
        }
        return switch (field.type()) {
            case INTEGER -> value instanceof Number n && isIntegral(n)
                    && isNumberInRange(field, n.doubleValue());
            case NUMBER -> value instanceof Number n && isNumberInRange(field, n.doubleValue());
            case BOOLEAN -> value instanceof Boolean;
            case DATETIME -> value instanceof String s && ValueSupport.isIsoDateTime(s);
            case REFERENCE -> value instanceof String s && !s.isBlank();
            default -> false;
        };
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof java.math.BigInteger
                || n.doubleValue() == Math.rint(n.doubleValue());
    }

    private static boolean isNumberInRange(FieldDefinition field, double v) {
        if (field.min() != null && v < field.min()) {
            return false;
        }
        return field.max() == null || v <= field.max();
    }

    /** Check a STRING value's length and regex constraints (non-localized raw value). */
    public static boolean isStringInRange(FieldDefinition field, Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        if (field.minLength() != null && s.length() < field.minLength()) {
            return false;
        }
        if (field.maxLength() != null && s.length() > field.maxLength()) {
            return false;
        }
        if (field.pattern() != null && !field.pattern().isBlank()
                && !Pattern.matches(field.pattern(), s)) {
            return false;
        }
        return true;
    }
}
