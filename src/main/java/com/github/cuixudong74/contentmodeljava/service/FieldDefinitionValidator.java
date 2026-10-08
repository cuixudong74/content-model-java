package com.github.cuixudong74.contentmodeljava.service;

import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.domain.FieldType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates a single field definition independent of any content type:
 * key syntax, per-type constraint consistency, localizability and default value consistency.
 * Cross-field concerns (duplicate keys) are handled by {@link ContentTypeService}.
 */
@Component
public class FieldDefinitionValidator {

    /** Field keys are snake/camel-ish identifiers starting with a letter. */
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /**
     * @return human-readable validation error messages; empty means the definition is valid.
     */
    public List<String> validate(FieldDefinition field) {
        List<String> errors = new ArrayList<>();
        if (field == null) {
            errors.add("field definition must not be null");
            return errors;
        }
        validateKey(field, errors);
        FieldType type = field.type();
        if (type == null) {
            errors.add(scope(field) + "type must not be null");
            return errors;
        }
        switch (type) {
            case STRING -> validateString(field, errors);
            case INTEGER, NUMBER -> validateNumeric(field, errors, type == FieldType.INTEGER);
            case BOOLEAN -> validateBoolean(field, errors);
            case DATETIME -> validateDatetime(field, errors);
            case REFERENCE -> validateReference(field, errors);
        }
        return errors;
    }

    private void validateKey(FieldDefinition field, List<String> errors) {
        String key = field.key();
        if (key == null || key.isBlank()) {
            errors.add("field key must not be empty");
        } else if (!KEY_PATTERN.matcher(key).matches()) {
            errors.add("field key '" + key + "' must match [A-Za-z][A-Za-z0-9_]*");
        }
    }

    private void validateString(FieldDefinition field, List<String> errors) {
        String scope = scope(field);
        if (field.minLength() != null && field.minLength() < 0) {
            errors.add(scope + "minLength must be >= 0");
        }
        if (field.maxLength() != null && field.maxLength() < 0) {
            errors.add(scope + "maxLength must be >= 0");
        }
        if (field.minLength() != null && field.maxLength() != null
                && field.minLength() > field.maxLength()) {
            errors.add(scope + "minLength (" + field.minLength()
                    + ") must not exceed maxLength (" + field.maxLength() + ")");
        }
        if (field.pattern() != null && !field.pattern().isBlank()) {
            try {
                Pattern.compile(field.pattern());
            } catch (PatternSyntaxException e) {
                errors.add(scope + "pattern is not a valid regular expression: " + e.getDescription());
            }
        }
        checkDefaultNotAllowed(field, errors, field.min(), field.max(), field.refType());
        if (field.defaultValue() != null) {
            if (!(field.defaultValue() instanceof String s)) {
                errors.add(scope + "default value of STRING field must be a string");
            } else {
                validateStringValue(field, s, errors, "default value");
            }
        }
    }

    private void validateNumeric(FieldDefinition field, List<String> errors, boolean integer) {
        String scope = scope(field);
        if (field.min() != null && field.max() != null && field.min() > field.max()) {
            errors.add(scope + "min (" + field.min() + ") must not exceed max (" + field.max() + ")");
        }
        checkDefaultNotAllowed(field, errors, field.minLength(), field.maxLength(),
                field.pattern(), field.refType());
        if (field.defaultValue() != null) {
            if (!(field.defaultValue() instanceof Number n)) {
                errors.add(scope + "default value of " + (integer ? "INTEGER" : "NUMBER")
                        + " field must be a number");
            } else {
                if (integer && !(field.defaultValue() instanceof Integer
                        || field.defaultValue() instanceof Long)) {
                    errors.add(scope + "default value of INTEGER field must be an integral number");
                } else {
                    validateNumberValue(field, n.doubleValue(), errors, "default value");
                }
            }
        }
    }

    private void validateBoolean(FieldDefinition field, List<String> errors) {
        checkDefaultNotAllowed(field, errors, field.minLength(), field.maxLength(), field.pattern(),
                field.min(), field.max(), field.refType());
        if (field.defaultValue() != null && !(field.defaultValue() instanceof Boolean)) {
            errors.add(scope(field) + "default value of BOOLEAN field must be a boolean");
        }
    }

    private void validateDatetime(FieldDefinition field, List<String> errors) {
        String scope = scope(field);
        checkDefaultNotAllowed(field, errors, field.minLength(), field.maxLength(), field.pattern(),
                field.min(), field.max(), field.refType());
        if (field.defaultValue() != null) {
            if (!(field.defaultValue() instanceof String s)) {
                errors.add(scope + "default value of DATETIME field must be an ISO-8601 string");
            } else if (!ValueSupport.isIsoDateTime(s)) {
                errors.add(scope + "default value '" + s + "' is not a valid ISO-8601 date-time");
            }
        }
    }

    private void validateReference(FieldDefinition field, List<String> errors) {
        String scope = scope(field);
        if (field.refType() == null || field.refType().isBlank()) {
            errors.add(scope + "REFERENCE field must declare a non-empty refType");
        }
        checkDefaultNotAllowed(field, errors, field.minLength(), field.maxLength(), field.pattern(),
                field.min(), field.max());
        if (field.localized()) {
            errors.add(scope + "REFERENCE field cannot be localized");
        }
        if (field.defaultValue() != null && !(field.defaultValue() instanceof String)) {
            errors.add(scope + "default value of REFERENCE field must be an entry id string");
        }
    }

    private void checkDefaultNotAllowed(FieldDefinition field, List<String> errors, Object... attrs) {
        String scope = scope(field);
        for (Object attr : attrs) {
            if (attr != null) {
                errors.add(scope + "constraint attribute is not applicable to "
                        + field.type() + " fields");
                return;
            }
        }
    }

    private void validateStringValue(FieldDefinition field, String value, List<String> errors,
                                     String what) {
        String scope = scope(field);
        if (field.minLength() != null && value.length() < field.minLength()) {
            errors.add(scope + what + " length " + value.length() + " is below minLength "
                    + field.minLength());
        }
        if (field.maxLength() != null && value.length() > field.maxLength()) {
            errors.add(scope + what + " length " + value.length() + " exceeds maxLength "
                    + field.maxLength());
        }
        if (field.pattern() != null && !field.pattern().isBlank()
                && !Pattern.matches(field.pattern(), value)) {
            errors.add(scope + what + " does not match pattern " + field.pattern());
        }
    }

    private void validateNumberValue(FieldDefinition field, double value, List<String> errors,
                                     String what) {
        String scope = scope(field);
        if (field.min() != null && value < field.min()) {
            errors.add(scope + what + " " + value + " is below min " + field.min());
        }
        if (field.max() != null && value > field.max()) {
            errors.add(scope + what + " " + value + " exceeds max " + field.max());
        }
    }

    private String scope(FieldDefinition field) {
        String key = field.key() == null ? "?" : field.key();
        return "field '" + key + "': ";
    }
}
