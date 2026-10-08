package com.github.cuixudong74.contentmodeljava.model;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable definition of a single field inside a {@link ContentType}.
 *
 * <p>Constraints by field type:
 * <ul>
 *     <li>STRING / LOCALIZED_STRING: {@code maxLength}, {@code allowedValues} (enum range)</li>
 *     <li>INTEGER / NUMBER: {@code min}, {@code max} numeric range</li>
 *     <li>REFERENCE: {@code referencedTypeId} names the target content type</li>
 * </ul>
 * A field with a non-null {@code defaultValue} is effectively optional: entries
 * created before the field existed get the value projected on read.
 */
public final class FieldDefinition {

    private final String id;
    private final String name;
    private final FieldType type;
    private final boolean required;
    private final boolean localized;
    private final Object defaultValue;
    private final Integer maxLength;
    private final Double min;
    private final Double max;
    private final List<String> allowedValues;
    private final String referencedTypeId;

    public FieldDefinition(String id, String name, FieldType type, boolean required, boolean localized,
                           Object defaultValue, Integer maxLength, Double min, Double max,
                           List<String> allowedValues, String referencedTypeId) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.required = required;
        this.localized = localized;
        this.defaultValue = defaultValue;
        this.maxLength = maxLength;
        this.min = min;
        this.max = max;
        this.allowedValues = allowedValues;
        this.referencedTypeId = referencedTypeId;
    }

    @JsonProperty("id")
    public String id() { return id; }
    @JsonProperty("name")
    public String name() { return name; }
    @JsonProperty("type")
    public FieldType type() { return type; }
    @JsonProperty("required")
    public boolean required() { return required; }
    @JsonProperty("localized")
    public boolean localized() { return localized; }
    @JsonProperty("defaultValue")
    public Object defaultValue() { return defaultValue; }
    @JsonProperty("maxLength")
    public Integer maxLength() { return maxLength; }
    @JsonProperty("min")
    public Double min() { return min; }
    @JsonProperty("max")
    public Double max() { return max; }
    @JsonProperty("allowedValues")
    public List<String> allowedValues() { return allowedValues; }
    @JsonProperty("referencedTypeId")
    public String referencedTypeId() { return referencedTypeId; }

    /**
     * Whether this field is "safe" to add to a content type that already has
     * persisted entries. A field is safe when it is optional or carries a
     * default value; a new required field without default would invalidate
     * existing entries and is therefore rejected.
     */
    public boolean safeToAddToExistingType() {
        return !required || defaultValue != null;
    }

    public FieldDefinition withRequired(boolean newRequired) {
        return new FieldDefinition(id, name, type, newRequired, localized, defaultValue,
                maxLength, min, max, allowedValues, referencedTypeId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FieldDefinition that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
