package com.github.cuixudong74.contentmodeljava.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

/**
 * A content entry of a given {@link ContentType}.
 *
 * <p>An entry carries two independently versioned data snapshots:
 * <ul>
 *     <li>{@code draft} — mutable working copy plus {@code draftVersion} for
 *     optimistic concurrency control,</li>
 *     <li>{@code published} — immutable snapshot of a <em>specific</em> draft
 *     version ({@code publishedBaseDraftVersion}), visible to published
 *     readers. {@code publishedVersion} increments on every publication.</li>
 * </ul>
 * Draft edits are never visible through published reads. Publication is
 * performed by atomically copying the draft snapshot, so a failed validation
 * leaves the previously published version (if any) untouched.
 */
public final class Entry {

    private final String id;
    private final String typeId;
    private long draftVersion;
    private long publishedVersion;
    private Long publishedBaseDraftVersion;
    private ObjectNode draft;
    private ObjectNode published;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;

    public Entry(String id, String typeId, ObjectNode initialDraft, Instant now) {
        this.id = Objects.requireNonNull(id, "entry id");
        this.typeId = Objects.requireNonNull(typeId, "type id");
        this.draftVersion = 1;
        this.publishedVersion = 0;
        this.publishedBaseDraftVersion = null;
        this.draft = Objects.requireNonNull(initialDraft, "initial draft");
        this.published = null;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String id() { return id; }
    public String typeId() { return typeId; }
    public long draftVersion() { return draftVersion; }
    public long publishedVersion() { return publishedVersion; }
    public Long publishedBaseDraftVersion() { return publishedBaseDraftVersion; }
    public ObjectNode draft() { return draft; }
    public ObjectNode published() { return published; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant publishedAt() { return publishedAt; }

    public boolean isPublished() { return published != null; }

    public void updateDraft(ObjectNode newDraft, Instant now) {
        this.draft = newDraft;
        this.draftVersion++;
        this.updatedAt = now;
    }

    /** Atomically publish a snapshot of the given draft version. */
    public void publish(JsonNode snapshot, long baseDraftVersion, Instant now) {
        this.published = (ObjectNode) snapshot;
        this.publishedBaseDraftVersion = baseDraftVersion;
        this.publishedVersion++;
        this.publishedAt = now;
    }

    public void unpublish(Instant now) {
        this.published = null;
        this.publishedBaseDraftVersion = null;
        this.publishedAt = null;
        this.updatedAt = now;
    }
}
