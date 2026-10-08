package com.github.cuixudong74.contentmodeljava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.cuixudong74.contentmodeljava.error.ContentModelException;
import com.github.cuixudong74.contentmodeljava.error.ErrorCode;
import com.github.cuixudong74.contentmodeljava.model.ContentType;
import com.github.cuixudong74.contentmodeljava.model.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.model.FieldType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates field definitions and entry data against a content type, and
 * extracts entry references held in entry data.
 *
 * <p>Value shapes on the JSON payload:
 * <ul>
 *     <li>STRING / LOCALIZED_STRING: string / object mapping language tag to string</li>
 *     <li>INTEGER: integral JSON number; NUMBER: any JSON number</li>
 *     <li>BOOLEAN: JSON boolean; DATE: {@code yyyy-MM-dd} string</li>
 *     <li>REFERENCE: a single entry-id string, or an array of entry-id strings</li>
 * </ul>
 */
@Component
public class ValueValidator {

    private static final Pattern FIELD_ID = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * Validate a field definition on its own (type-specific constraints and a
     * type-conforming default value).
     */
    public void validateDefinition(FieldDefinition field) {
        List<String> errors = new ArrayList<>();

        if (field.id() == null || field.id().isBlank()) {
            errors.add("field id must not be blank");
        } else if (!FIELD_ID.matcher(field.id()).matches()) {
            errors.add("field id '" + field.id() + "' must match " + FIELD_ID.pattern());
        }
        if (field.name() == null || field.name().isBlank()) {
            errors.add("field '" + field.id() + "' name must not be blank");
        }
        if (field.type() == null) {
            errors.add("field '" + field.id() + "' type must not be null");
            throw new ContentModelException(ErrorCode.FIELD_INVALID_DEFINITION,
                    "invalid field definition", errors);
        }

        switch (field.type()) {
            case STRING, LOCALIZED_STRING -> {
                if (field.maxLength() != null && field.maxLength() <= 0) {
                    errors.add("field '" + field.id() + "' maxLength must be positive");
                }
                if (field.allowedValues() != null) {
                    for (String v : field.allowedValues()) {
                        if (v == null || v.isEmpty()) {
                            errors.add("field '" + field.id() + "' allowedValues must not contain blank values");
                        } else if (field.maxLength() != null && v.length() > field.maxLength()) {
                            errors.add("field '" + field.id() + "' allowed value '" + v
                                    + "' exceeds maxLength " + field.maxLength());
                        }
                    }
                }
                if (field.min() != null || field.max() != null || field.referencedTypeId() != null) {
                    errors.add("field '" + field.id() + "': min/max/referencedTypeId only apply to other types");
                }
            }
            case INTEGER, NUMBER -> {
                if (field.min() != null && field.max() != null && field.min() > field.max()) {
                    errors.add("field '" + field.id() + "' min (" + field.min()
                            + ") must not exceed max (" + field.max() + ")");
                }
                if (field.maxLength() != null || field.referencedTypeId() != null || field.allowedValues() != null) {
                    errors.add("field '" + field.id() + "': only min/max constraints apply");
                }
            }
            case BOOLEAN, DATE -> {
                if (field.maxLength() != null || field.min() != null || field.max() != null
                        || field.allowedValues() != null || field.referencedTypeId() != null) {
                    errors.add("field '" + field.id() + "': no constraints apply to " + field.type());
                }
            }
            case REFERENCE -> {
                if (field.referencedTypeId() == null || field.referencedTypeId().isBlank()) {
                    errors.add("field '" + field.id() + "' REFERENCE requires referencedTypeId");
                }
                if (field.maxLength() != null || field.min() != null || field.max() != null
                        || field.allowedValues() != null) {
                    errors.add("field '" + field.id() + "': REFERENCE only supports referencedTypeId");
                }
            }
        }

        if (field.defaultValue() instanceof JsonNode node && !node.isNull()) {
            List<String> valueErrors = new ArrayList<>();
            checkValue(field, node, valueErrors);
            if (!valueErrors.isEmpty()) {
                errors.add("field '" + field.id() + "' default value invalid: " + String.join("; ", valueErrors));
            }
        }

        if (!errors.isEmpty()) {
            throw new ContentModelException(ErrorCode.FIELD_INVALID_DEFINITION,
                    "invalid field definition for '" + field.id() + "'", errors);
        }
    }

    /** Validate a full data object; collects all field errors before failing. */
    public void validateData(ContentType type, ObjectNode data) {
        List<String> errors = new ArrayList<>();
        for (FieldDefinition field : type.fields()) {
            JsonNode value = data.get(field.id());
            if (value == null || value.isNull()) {
                if (field.required() && field.defaultValue() == null) {
                    errors.add("field '" + field.id() + "' is required");
                }
                continue;
            }
            checkValue(field, value, errors);
        }
        if (!errors.isEmpty()) {
            throw new ContentModelException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "entry data validation failed for type '" + type.id() + "'", errors);
        }
    }

    /** Validate a single non-null field value, appending human-readable errors. */
    public void validateValue(FieldDefinition field, JsonNode value) {
        List<String> errors = new ArrayList<>();
        checkValue(field, value, errors);
        if (!errors.isEmpty()) {
            throw new ContentModelException(ErrorCode.FIELD_VALIDATION_FAILED,
                    "invalid value for field '" + field.id() + "'", errors);
        }
    }

    private void checkValue(FieldDefinition field, JsonNode value, List<String> errors) {
        String id = field.id();
        switch (field.type()) {
            case STRING -> {
                if (!value.isTextual()) {
                    errors.add("field '" + id + "' expects a string");
                    break;
                }
                String s = value.asText();
                if (field.maxLength() != null && s.length() > field.maxLength()) {
                    errors.add("field '" + id + "' length " + s.length() + " exceeds maxLength " + field.maxLength());
                }
                if (field.allowedValues() != null && !field.allowedValues().contains(s)) {
                    errors.add("field '" + id + "' value '" + s + "' is not in allowedValues " + field.allowedValues());
                }
            }
            case LOCALIZED_STRING -> {
                if (!value.isObject()) {
                    errors.add("field '" + id + "' expects an object mapping language tag to string");
                    break;
                }
                value.fields().forEachRemaining(e -> {
                    if (!e.getValue().isTextual()) {
                        errors.add("field '" + id + "' translation '" + e.getKey() + "' must be a string");
                        return;
                    }
                    String s = e.getValue().asText();
                    if (field.maxLength() != null && s.length() > field.maxLength()) {
                        errors.add("field '" + id + "'[" + e.getKey() + "] length " + s.length()
                                + " exceeds maxLength " + field.maxLength());
                    }
                    if (field.allowedValues() != null && !field.allowedValues().contains(s)) {
                        errors.add("field '" + id + "'[" + e.getKey() + "] value '" + s
                                + "' is not in allowedValues " + field.allowedValues());
                    }
                });
            }
            case INTEGER -> {
                if (!value.isIntegralNumber()) {
                    errors.add("field '" + id + "' expects an integer");
                    break;
                }
                checkRange(field, value.asDouble(), errors);
            }
            case NUMBER -> {
                if (!value.isNumber()) {
                    errors.add("field '" + id + "' expects a number");
                    break;
                }
                checkRange(field, value.asDouble(), errors);
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    errors.add("field '" + id + "' expects a boolean");
                }
            }
            case DATE -> {
                if (!value.isTextual() || !DATE.matcher(value.asText()).matches() || !parseableDate(value.asText())) {
                    errors.add("field '" + id + "' expects a date in yyyy-MM-dd format");
                }
            }
            case REFERENCE -> {
                List<String> refs = new ArrayList<>();
                collectRefIds(value, refs, id, errors);
                if (refs.isEmpty()) {
                    errors.add("field '" + id + "' REFERENCE must reference at least one entry");
                }
            }
        }
    }

    private void checkRange(FieldDefinition field, double v, List<String> errors) {
        if (field.min() != null && v < field.min()) {
            errors.add("field '" + field.id() + "' value " + v + " is below min " + field.min());
        }
        if (field.max() != null && v > field.max()) {
            errors.add("field '" + field.id() + "' value " + v + " exceeds max " + field.max());
        }
    }

    private boolean parseableDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private void collectRefIds(JsonNode value, List<String> out, String fieldId, List<String> errors) {
        if (value.isTextual()) {
            out.add(value.asText());
        } else if (value.isArray()) {
            for (JsonNode element : value) {
                if (!element.isTextual()) {
                    errors.add("field '" + fieldId + "' reference array must contain only entry-id strings");
                } else {
                    out.add(element.asText());
                }
            }
        } else {
            errors.add("field '" + fieldId + "' expects an entry-id string or an array of entry ids");
        }
    }

    /** Extract referenced entry ids keyed by reference field id from a data object. */
    public Map<String, List<String>> extractReferences(ContentType type, ObjectNode data) {
        Map<String, List<String>> refs = new LinkedHashMap<>();
        for (FieldDefinition field : type.fields()) {
            if (field.type() != FieldType.REFERENCE) continue;
            JsonNode value = data.get(field.id());
            if (value == null || value.isNull()) continue;
            List<String> ids = new ArrayList<>();
            List<String> ignored = new ArrayList<>();
            collectRefIds(value, ids, field.id(), ignored);
            if (!ids.isEmpty()) refs.put(field.id(), ids);
        }
        return refs;
    }
}
