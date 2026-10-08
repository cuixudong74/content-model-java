package com.github.cuixudong74.contentmodeljava.service;

import com.github.cuixudong74.contentmodeljava.domain.ContentType;
import com.github.cuixudong74.contentmodeljava.domain.Entry;
import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.domain.FieldType;
import com.github.cuixudong74.contentmodeljava.domain.ReferencePolicy;
import com.github.cuixudong74.contentmodeljava.exception.ContentModelException;
import com.github.cuixudong74.contentmodeljava.exception.ErrorCode;
import com.github.cuixudong74.contentmodeljava.store.ContentTypeStore;
import com.github.cuixudong74.contentmodeljava.store.EntryStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-entry reference rules.
 *
 * <p>At publish time every REFERENCE value must point to an entry that exists, is published and
 * has the declared refType. Missing and unpublished targets are reported with distinguishable
 * error codes so clients can react differently.
 *
 * <p>When a published target is unpublished or deleted, every <em>published</em> referrer is
 * either blocked (RESTRICT, the default policy) or unpublished/deleted itself (CASCADE). Draft
 * references are never blocked: drafts are work in progress and are re-validated at publish.
 */
@Service
public class ReferenceIntegrityService {

    private final EntryStore entryStore;
    private final ContentTypeStore contentTypeStore;

    public ReferenceIntegrityService(EntryStore entryStore, ContentTypeStore contentTypeStore) {
        this.entryStore = entryStore;
        this.contentTypeStore = contentTypeStore;
    }

    /**
     * Validate references in the values about to be published.
     *
     * @throws ContentModelException REFERENCE_TARGET_NOT_FOUND / REFERENCE_TARGET_NOT_PUBLISHED /
     *                               VALIDATION_FAILED with a list of per-field reasons
     */
    public void validateForPublish(String entryId, String typeId, Map<String, Object> values) {
        ContentType type = contentTypeStore.get(typeId);
        List<Map<String, String>> reasons = new ArrayList<>();
        synchronized (type) {
            for (FieldDefinition field : type.fieldList()) {
                if (field.type() != FieldType.REFERENCE) {
                    continue;
                }
                Object raw = values == null ? null : values.get(field.key());
                if (raw == null) {
                    continue;
                }
                if (!(raw instanceof String targetId)) {
                    reasons.add(reason(field, "reference must be an entry id string",
                            "INVALID_SHAPE", String.valueOf(raw)));
                    continue;
                }
                if (targetId.equals(entryId)) {
                    reasons.add(reason(field, "entry must not reference itself",
                            "SELF_REFERENCE", targetId));
                    continue;
                }
                Entry target = entryStore.find(targetId).orElse(null);
                if (target == null) {
                    reasons.add(reason(field, "referenced entry '" + targetId + "' does not exist",
                            ErrorCode.REFERENCE_TARGET_NOT_FOUND.name(), targetId));
                    continue;
                }
                if (!target.isPublished()) {
                    reasons.add(reason(field,
                            "referenced entry '" + targetId + "' is not published",
                            ErrorCode.REFERENCE_TARGET_NOT_PUBLISHED.name(), targetId));
                    continue;
                }
                if (field.refType() != null && !field.refType().equals(target.getTypeId())) {
                    reasons.add(reason(field, "referenced entry '" + targetId
                                    + "' has type '" + target.getTypeId() + "' but expected '"
                                    + field.refType() + "'",
                            "REFERENCE_TYPE_MISMATCH", targetId));
                }
            }
        }
        if (reasons.isEmpty()) {
            return;
        }
        ErrorCode code = resolveCode(reasons);
        throw new ContentModelException(code,
                "references of entry '" + entryId + "' cannot be published", reasons);
    }

    /**
     * Enforce referential policy before a target is unpublished ({@code deleting=false}) or
     * deleted ({@code deleting=true}). Published referrers are collected; RESTRICT fields block
     * the operation, CASCADE fields make the referrer undergo the same operation recursively.
     */
    public void beforeTargetRemove(String targetEntryId, boolean deleting) {
        List<Map<String, String>> blockers = new ArrayList<>();
        List<Entry> cascadeEntries = new ArrayList<>();

        for (Entry referrer : entryStore.findAll()) {
            if (!referrer.isPublished() || referrer.getId().equals(targetEntryId)) {
                continue;
            }
            ContentType type = contentTypeStore.find(referrer.getTypeId()).orElse(null);
            if (type == null) {
                continue;
            }
            synchronized (type) {
                for (FieldDefinition field : type.fieldList()) {
                    if (field.type() != FieldType.REFERENCE) {
                        continue;
                    }
                    Object raw = referrer.getPublishedValues().get(field.key());
                    if (!targetEntryId.equals(raw)) {
                        continue;
                    }
                    if (field.effectivePolicy() == ReferencePolicy.CASCADE) {
                        cascadeEntries.add(referrer);
                    } else {
                        blockers.add(Map.of(
                                "field", field.key(),
                                "referrerEntryId", referrer.getId(),
                                "policy", ReferencePolicy.RESTRICT.name()));
                    }
                }
            }
        }

        if (!blockers.isEmpty()) {
            throw new ContentModelException(ErrorCode.REFERENCE_INTEGRITY_VIOLATION,
                    (deleting ? "delete" : "unpublish") + " of entry '" + targetEntryId
                            + "' is blocked by RESTRICT references", blockers);
        }

        // Apply cascades. A deleting cascade removes the entry; an unpublishing cascade only
        // clears the published snapshot, keeping drafts untouched. Chains resolve transitively
        // (a cascaded referrer may itself be a target for further referrers).
        Set<String> cascaded = new HashSet<>();
        List<String> frontier = cascadeEntries.stream().map(Entry::getId).toList();
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String referrerId : frontier) {
                if (!cascaded.add(referrerId)) {
                    continue;
                }
                Entry referrer = entryStore.find(referrerId).orElse(null);
                if (referrer == null || !referrer.isPublished()) {
                    continue;
                }
                synchronized (entryStore.lockFor(referrerId)) {
                    if (deleting) {
                        entryStore.delete(referrerId);
                    } else {
                        referrer.setPublishedValues(null);
                        referrer.setPublishedVersion(-1);
                        referrer.setSchemaVersionAtPublished(-1);
                    }
                }
                // Anything that referenced this referrer must cascade too.
                for (Entry above : entryStore.findAll()) {
                    if (!above.isPublished() || cascaded.contains(above.getId())) {
                        continue;
                    }
                    if (references(above, referrerId)) {
                        next.add(above.getId());
                    }
                }
            }
            frontier = next;
        }
    }

    private boolean references(Entry referrer, String targetId) {
        ContentType type = contentTypeStore.find(referrer.getTypeId()).orElse(null);
        if (type == null) {
            return false;
        }
        synchronized (type) {
            for (FieldDefinition field : type.fieldList()) {
                if (field.type() == FieldType.REFERENCE
                        && targetId.equals(referrer.getPublishedValues().get(field.key()))) {
                    // Only follow the chain when the field cascades; restrict fields on this
                    // intermediate node are reported on the next top-level invocation.
                    return field.effectivePolicy() == ReferencePolicy.CASCADE;
                }
            }
        }
        return false;
    }

    private Map<String, String> reason(FieldDefinition field, String message,
                                       String reason, String targetEntryId) {
        return Map.of(
                "field", field.key(),
                "reason", reason,
                "targetEntryId", targetEntryId,
                "message", message);
    }

    /**
     * Deterministic, explainable top-level code: a structural not-found is never hidden behind a
     * mere not-published error. When both occur (different fields), NOT_FOUND wins.
     */
    private ErrorCode resolveCode(List<Map<String, String>> reasons) {
        boolean shapeError = false;
        for (Map<String, String> r : reasons) {
            if (ErrorCode.REFERENCE_TARGET_NOT_FOUND.name().equals(r.get("reason"))) {
                return ErrorCode.REFERENCE_TARGET_NOT_FOUND;
            }
            if ("INVALID_SHAPE".equals(r.get("reason")) || "SELF_REFERENCE".equals(r.get("reason"))
                    || "REFERENCE_TYPE_MISMATCH".equals(r.get("reason"))) {
                shapeError = true;
            }
        }
        for (Map<String, String> r : reasons) {
            if (ErrorCode.REFERENCE_TARGET_NOT_PUBLISHED.name().equals(r.get("reason"))) {
                return ErrorCode.REFERENCE_TARGET_NOT_PUBLISHED;
            }
        }
        return shapeError ? ErrorCode.VALIDATION_FAILED
                : ErrorCode.REFERENCE_TARGET_NOT_PUBLISHED;
    }
}
