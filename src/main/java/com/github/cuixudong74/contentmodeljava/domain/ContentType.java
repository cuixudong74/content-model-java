package com.github.cuixudong74.contentmodeljava.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named, versioned set of {@link FieldDefinition}s. Version increases on every
 * accepted evolution so stored entries can remember the schema version they were written against.
 */
public final class ContentType {

    private final String id;
    private final String displayName;
    private long version;
    private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();

    public ContentType(String id, String displayName, long version) {
        this.id = id;
        this.displayName = displayName;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public long getVersion() {
        return version;
    }

    public void bumpVersion() {
        this.version++;
    }

    public Map<String, FieldDefinition> getFields() {
        return Collections.unmodifiableMap(fields);
    }

    public List<FieldDefinition> fieldList() {
        return new ArrayList<>(fields.values());
    }

    public FieldDefinition field(String key) {
        return fields.get(key);
    }

    public void putField(FieldDefinition field) {
        fields.put(field.key(), field);
    }

    public boolean hasField(String key) {
        return fields.containsKey(key);
    }
}
