package com.github.cuixudong74.contentmodeljava.service;

import com.github.cuixudong74.contentmodeljava.domain.ContentType;
import com.github.cuixudong74.contentmodeljava.domain.Entry;
import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.domain.FieldType;
import com.github.cuixudong74.contentmodeljava.exception.ContentModelException;
import com.github.cuixudong74.contentmodeljava.exception.ErrorCode;
import com.github.cuixudong74.contentmodeljava.store.ContentTypeStore;
import com.github.cuixudong74.contentmodeljava.store.EntryStore;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.EntryView;
import com.github.cuixudong74.contentmodeljava.web.dto.FieldValueView;
import com.github.cuixudong74.contentmodeljava.web.dto.PublishRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draft/publish lifecycle, optimistic concurrency and reads of content entries.
 *
 * <p>Each entry carries two independent version counters:
 * <ul>
 *   <li>{@code draftVersion} starts at 0 for a new entry and increases on every draft replace;</li>
 *   <li>{@code publishedVersion} is the draft version captured at the last successful publish
 *       (or absent when never published / after unpublish).</li>
 * </ul>
 * Draft writes are fully replaced maps; writes only validate value shapes so a draft may be
 * temporarily incomplete. Publishing atomically validates required fields and reference
 * integrity, then snapshots the draft — readers of PUBLISHED never observe intermediate state.
 */
@Service
public class EntryService {

    private final EntryStore entryStore;
    private final ContentTypeStore contentTypeStore;
    private final ProjectionService projectionService;
    private final ReferenceIntegrityService referenceIntegrityService;

    public EntryService(EntryStore entryStore, ContentTypeStore contentTypeStore,
                        ProjectionService projectionService,
                        ReferenceIntegrityService referenceIntegrityService) {
        this.entryStore = entryStore;
        this.contentTypeStore = contentTypeStore;
        this.projectionService = projectionService;
        this.referenceIntegrityService = referenceIntegrityService;
    }

    public EntryView create(String typeId, EntryRequest request) {
        ContentType type = contentTypeStore.get(typeId);
        Map<String, Object> values = sanitizeValues(request);
        validateDraftShapes(type, values);
        Entry entry = entryStore.create(typeId);
        synchronized (entryStore.lockFor(entry.getId())) {
            entry.setDraftValues(new LinkedHashMap<>(values));
            entry.setDraftVersion(1L);
            entry.setSchemaVersionAtDraft(currentSchemaVersion(type));
        }
        return view(type, entry, "DRAFT", null, List.of());
    }

    /**
     * Replace the draft. Requires {@code expectedVersion} to equal the current draft version;
     * a stale edit is rejected with VERSION_CONFLICT and the current version, so the client can
     * re-read, merge and retry.
     */
    public EntryView updateDraft(String typeId, String entryId, EntryRequest request) {
        ContentType type = contentTypeStore.get(typeId);
        Entry entry = entryStore.get(entryId);
        ensureTypeMatches(typeId, entry);
        Map<String, Object> values = sanitizeValues(request);
        validateDraftShapes(type, values);
        synchronized (entryStore.lockFor(entryId)) {
            long current = entry.getDraftVersion();
            Long expected = request == null ? null : request.expectedVersion();
            if (expected == null) {
                throw new ContentModelException(ErrorCode.VERSION_CONFLICT,
                        "expectedVersion is required when updating a draft",
                        conflictDetails(entryId, current, null));
            }
            if (expected != current) {
                throw new ContentModelException(ErrorCode.VERSION_CONFLICT,
                        "draft version " + expected + " is stale; current version is " + current,
                        conflictDetails(entryId, current, expected));
            }
            entry.setDraftValues(new LinkedHashMap<>(values));
            entry.setDraftVersion(current + 1);
            entry.setSchemaVersionAtDraft(currentSchemaVersion(type));
            return view(type, entry, "DRAFT", null, List.of());
        }
    }

    public EntryView get(String typeId, String entryId, String stage, String locale,
                         List<String> fallbackLocales) {
        ContentType type = contentTypeStore.get(typeId);
        Entry entry = entryStore.get(entryId);
        ensureTypeMatches(typeId, entry);
        return view(type, entry, normalizeStage(stage), locale, fallbackLocales);
    }

    public List<EntryView> list(String typeId, String stage, String locale,
                                List<String> fallbackLocales) {
        ContentType type = contentTypeStore.get(typeId);
        String normalized = normalizeStage(stage);
        List<EntryView> views = new ArrayList<>();
        for (Entry entry : entryStore.findByType(typeId)) {
            if ("PUBLISHED".equals(normalized) && !entry.isPublished()) {
                continue;
            }
            views.add(view(type, entry, normalized, locale, fallbackLocales));
        }
        return views;
    }

    /**
     * Publish one definite draft version. The whole operation runs under the entry lock and
     * performs every check before mutating the published snapshot, so a failure leaves any
     * previously published version intact (no half-published state).
     */
    public EntryView publish(String typeId, String entryId, PublishRequest request) {
        ContentType type = contentTypeStore.get(typeId);
        Entry entry = entryStore.get(entryId);
        ensureTypeMatches(typeId, entry);
        synchronized (entryStore.lockFor(entryId)) {
            long currentDraft = entry.getDraftVersion();
            if (request != null && request.expectedDraftVersion() != null
                    && request.expectedDraftVersion() != currentDraft) {
                throw new ContentModelException(ErrorCode.VERSION_CONFLICT,
                        "draft version " + request.expectedDraftVersion()
                                + " is stale; current draft version is " + currentDraft,
                        conflictDetails(entryId, currentDraft, request.expectedDraftVersion()));
            }
            Map<String, Object> values = entry.getDraftValues();
            validateForPublish(type, entryId, values);
            // Reference checks touch other entries but the entry stays unpublished until they pass.
            referenceIntegrityService.validateForPublish(entryId, typeId, values);

            entry.setPublishedValues(new LinkedHashMap<>(values));
            entry.setPublishedVersion(currentDraft);
            entry.setSchemaVersionAtPublished(currentSchemaVersion(type));
            return view(type, entry, "PUBLISHED", null, List.of());
        }
    }

    public void unpublish(String typeId, String entryId) {
        ContentType type = contentTypeStore.get(typeId);
        Entry entry = entryStore.get(entryId);
        ensureTypeMatches(typeId, entry);
        synchronized (entryStore.lockFor(entryId)) {
            if (!entry.isPublished()) {
                throw new ContentModelException(ErrorCode.ENTRY_NOT_PUBLISHED,
                        "entry '" + entryId + "' is not published");
            }
            // Enforce reference policy against the published graph first; no state changes on failure.
            referenceIntegrityService.beforeTargetRemove(entryId, false);
            entry.setPublishedValues(null);
            entry.setPublishedVersion(-1);
            entry.setSchemaVersionAtPublished(-1);
        }
    }

    /**
     * Delete the entry (both draft and published). Blocked when any RESTRICT published referrer
     * still points at it; CASCADE referrers are removed as well.
     */
    public void delete(String typeId, String entryId) {
        ContentType type = contentTypeStore.get(typeId);
        Entry entry = entryStore.get(entryId);
        ensureTypeMatches(typeId, entry);
        synchronized (entryStore.lockFor(entryId)) {
            if (entry.isPublished()) {
                referenceIntegrityService.beforeTargetRemove(entryId, true);
            }
            entryStore.delete(entryId);
        }
    }

    // ------------------------------------------------------------------

    private Map<String, Object> sanitizeValues(EntryRequest request) {
        if (request == null || request.values() == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(request.values());
    }

    /**
     * Draft-time validation: unknown fields are rejected (they would silently never project),
     * and every provided value must satisfy its current shape/constraints. Required-ness is NOT
     * enforced here so drafts can remain works in progress.
     */
    private void validateDraftShapes(ContentType type, Map<String, Object> values) {
        List<String> errors = new ArrayList<>();
        synchronized (type) {
            for (String key : values.keySet()) {
                FieldDefinition field = type.field(key);
                if (field == null) {
                    errors.add("unknown field '" + key + "' for content type '" + type.getId() + "'");
                }
            }
            for (FieldDefinition field : type.fieldList()) {
                Object value = values.get(field.key());
                if (value == null) {
                    continue;
                }
                if (!ValueValidator.isShapeValid(field, value)) {
                    errors.add("field '" + field.key() + "': value " + describe(value)
                            + " is not a valid " + field.type()
                            + (field.localized() ? " localized value" : ""));
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ContentModelException(ErrorCode.VALIDATION_FAILED,
                    "draft values are invalid", errors);
        }
    }

    /** Publish-time local validation: every required field must hold a currently valid value. */
    private void validateForPublish(ContentType type, String entryId, Map<String, Object> values) {
        List<String> errors = new ArrayList<>();
        synchronized (type) {
            for (FieldDefinition field : type.fieldList()) {
                Object value = values.get(field.key());
                boolean emptyLocalized = field.localized()
                        && (value == null || (value instanceof Map<?, ?> m && m.isEmpty()));
                if (value == null || emptyLocalized) {
                    if (field.required() && field.defaultValue() == null) {
                        errors.add("field '" + field.key() + "' is required but not set");
                    }
                    continue;
                }
                if (!ValueValidator.isShapeValid(field, value)) {
                    errors.add("field '" + field.key() + "': value " + describe(value)
                            + " is not a valid " + field.type());
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new ContentModelException(ErrorCode.VALIDATION_FAILED,
                    "entry '" + entryId + "' is not publishable", errors);
        }
    }

    private EntryView view(ContentType type, Entry entry, String stage, String locale,
                           List<String> fallbacks) {
        synchronized (entryStore.lockFor(entry.getId())) {
            if ("PUBLISHED".equals(stage)) {
                if (!entry.isPublished()) {
                    throw new ContentModelException(ErrorCode.ENTRY_NOT_PUBLISHED,
                            "entry '" + entry.getId() + "' has no published version");
                }
                Map<String, FieldValueView> projected =
                        project(type, entry.getPublishedValues(), locale, fallbacks);
                return new EntryView(entry.getId(), entry.getTypeId(), "PUBLISHED",
                        entry.getPublishedVersion(), entry.getSchemaVersionAtPublished(), projected);
            }
            Map<String, FieldValueView> projected =
                    project(type, entry.getDraftValues(), locale, fallbacks);
            return new EntryView(entry.getId(), entry.getTypeId(), "DRAFT",
                    entry.getDraftVersion(), entry.getSchemaVersionAtDraft(), projected);
        }
    }

    private Map<String, FieldValueView> project(ContentType type, Map<String, Object> values,
                                                String locale, List<String> fallbacks) {
        if (locale != null && !locale.isBlank()) {
            return projectionService.project(type, values, locale, fallbacks);
        }
        return projectionService.project(type, values);
    }

    private void ensureTypeMatches(String typeId, Entry entry) {
        if (!entry.getTypeId().equals(typeId)) {
            throw new ContentModelException(ErrorCode.ENTRY_NOT_FOUND,
                    "entry '" + entry.getId() + "' does not belong to content type '" + typeId + "'");
        }
    }

    private String normalizeStage(String stage) {
        if (stage == null || stage.isBlank() || "DRAFT".equalsIgnoreCase(stage)) {
            return "DRAFT";
        }
        if ("PUBLISHED".equalsIgnoreCase(stage)) {
            return "PUBLISHED";
        }
        throw new ContentModelException(ErrorCode.VALIDATION_FAILED,
                "stage must be DRAFT or PUBLISHED but was '" + stage + "'");
    }

    private long currentSchemaVersion(ContentType type) {
        synchronized (type) {
            return type.getVersion();
        }
    }

    private Map<String, Object> conflictDetails(String entryId, long currentVersion,
                                                Long suppliedVersion) {
        Map<String, Object> details = new HashMap<>();
        details.put("entryId", entryId);
        details.put("currentVersion", currentVersion);
        details.put("suppliedVersion", suppliedVersion);
        details.put("retry", "re-read the draft, merge your changes and resubmit with expectedVersion="
                + currentVersion);
        return details;
    }

    private String describe(Object value) {
        if (value instanceof Map) {
            return "<localized map>";
        }
        String text = String.valueOf(value);
        return text.length() > 60 ? text.substring(0, 57) + "..." : text;
    }
}
