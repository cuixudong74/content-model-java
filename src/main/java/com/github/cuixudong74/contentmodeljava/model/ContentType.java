package com.github.cuixudong74.contentmodeljava.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A content type: an identified, versioned collection of field definitions.
 *
 * <p>Evolution model: every accepted change produces a new monotonic
 * {@code version}. Only safe evolution is accepted once entries exist
 * (see {@link FieldDefinition#safeToAddToExistingType()}).
 */
public final class ContentType {

    private final String id;
    private final String displayName;
    private long version;
    private final List<FieldDefinition> fields = new ArrayList<>();

    public ContentType(String id, String displayName, long version) {
        this.id = Objects.requireNonNull(id, "content type id");
        this.displayName = displayName;
        this.version = version;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public long version() { return version; }
    public void bumpVersion() { this.version++; }

    public List<FieldDefinition> fields() {
        return Collections.unmodifiableList(fields);
    }

    public FieldDefinition field(String fieldId) {
        for (FieldDefinition f : fields) {
            if (f.id().equals(fieldId)) return f;
        }
        return null;
    }

    public boolean hasField(String fieldId) { return field(fieldId) != null; }

    public void addField(FieldDefinition field) {
        Objects.requireNonNull(field, "field");
        if (hasField(field.id())) {
            throw new IllegalArgumentException("duplicate field id: " + field.id());
        }
        fields.add(field);
    }

    public void replaceField(FieldDefinition updated) {
        for (int i = 0; i < fields.size(); i++) {
            if (fields.get(i).id().equals(updated.id())) {
                fields.set(i, updated);
                return;
            }
        }
        throw new IllegalArgumentException("unknown field id: " + updated.id());
    }

    public void removeField(String fieldId) {
        fields.removeIf(f -> f.id().equals(fieldId));
    }
}
