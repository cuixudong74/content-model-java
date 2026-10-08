package com.github.cuixudong74.contentmodeljava.domain;

/**
 * Immutable definition of one field within a {@link ContentType}.
 *
 * <p>Constraints are interpreted per {@link FieldType}:
 * <ul>
 *   <li>STRING: minLength, maxLength, pattern (regex)</li>
 *   <li>INTEGER/NUMBER: min, max</li>
 *   <li>DATETIME: value must be an ISO-8601 date-time</li>
 *   <li>BOOLEAN: no constraints</li>
 *   <li>REFERENCE: refType constrains the referenced entry's content type; onDeletePolicy
 *       controls behaviour when the target is unpublished/deleted</li>
 * </ul>
 *
 * <p>{@code localized} is only meaningful for STRING.
 */
public record FieldDefinition(
        String key,
        FieldType type,
        boolean required,
        boolean localized,
        Object defaultValue,
        Integer minLength,
        Integer maxLength,
        String pattern,
        Double min,
        Double max,
        String refType,
        ReferencePolicy onDeletePolicy
) {

    public static FieldDefinition of(String key, FieldType type) {
        return new FieldDefinition(key, type, false, false, null,
                null, null, null, null, null, null, null);
    }

    /** Effective policy for references; RESTRICT when unspecified. */
    public ReferencePolicy effectivePolicy() {
        return onDeletePolicy == null ? ReferencePolicy.RESTRICT : onDeletePolicy;
    }
}
