package com.github.cuixudong74.contentmodeljava.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * A content entry with separate draft and published value maps, each guarded by an
 * optimistic-concurrency version. Values are plain JSON-compatible Maps/Lists/numbers/strings/booleans.
 */
public final class Entry {

    private final String id;
    private final String typeId;
    private long draftVersion;
    private Map<String, Object> draftValues = new HashMap<>();
    private long schemaVersionAtDraft;

    private long publishedVersion = -1;
    private Map<String, Object> publishedValues = null;
    private long schemaVersionAtPublished = -1;

    public Entry(String id, String typeId) {
        this.id = id;
        this.typeId = typeId;
    }

    public String getId() {
        return id;
    }

    public String getTypeId() {
        return typeId;
    }

    public long getDraftVersion() {
        return draftVersion;
    }

    public void setDraftVersion(long draftVersion) {
        this.draftVersion = draftVersion;
    }

    public Map<String, Object> getDraftValues() {
        return draftValues;
    }

    public void setDraftValues(Map<String, Object> draftValues) {
        this.draftValues = draftValues;
    }

    public long getSchemaVersionAtDraft() {
        return schemaVersionAtDraft;
    }

    public void setSchemaVersionAtDraft(long schemaVersionAtDraft) {
        this.schemaVersionAtDraft = schemaVersionAtDraft;
    }

    public long getPublishedVersion() {
        return publishedVersion;
    }

    public void setPublishedVersion(long publishedVersion) {
        this.publishedVersion = publishedVersion;
    }

    public Map<String, Object> getPublishedValues() {
        return publishedValues;
    }

    public void setPublishedValues(Map<String, Object> publishedValues) {
        this.publishedValues = publishedValues;
    }

    public long getSchemaVersionAtPublished() {
        return schemaVersionAtPublished;
    }

    public void setSchemaVersionAtPublished(long schemaVersionAtPublished) {
        this.schemaVersionAtPublished = schemaVersionAtPublished;
    }

    public boolean isPublished() {
        return publishedValues != null;
    }
}
