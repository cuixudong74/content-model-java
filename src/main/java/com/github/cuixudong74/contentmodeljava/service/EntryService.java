package com.github.cuixudong74.contentmodeljava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.cuixudong74.contentmodeljava.dto.DeleteEntryRequest;
import com.github.cuixudong74.contentmodeljava.dto.EntryRequest;
import com.github.cuixudong74.contentmodeljava.dto.PublishRequest;
import com.github.cuixudong74.contentmodeljava.dto.UnpublishRequest;
import com.github.cuixudong74.contentmodeljava.error.ContentModelException;
import com.github.cuixudong74.contentmodeljava.error.ErrorCode;
import com.github.cuixudong74.contentmodeljava.model.ContentType;
import com.github.cuixudong74.contentmodeljava.model.Entry;
import com.github.cuixudong74.contentmodeljava.model.EntryView;
import com.github.cuixudong74.contentmodeljava.model.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.model.ResolvedValue;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Entry lifecycle: draft editing with optimistic concurrency, version-pinned
 * publication, referential integrity on publish, and BLOCK / CASCADE_UNPUBLISH
 * strategies for unpublish and delete.
 *
 * <h3>Optimistic concurrency</h3>
 * Draft updates must carry {@code expectVersion} equal to the entry's current
 * {@code draftVersion}; a mismatch is rejected with {@code DRAFT_VERSION_CONFLICT}
 * carrying both versions so the caller can re-read and retry.
 *
 * <h3>Publishing</h3>
 * Publication requires the exact draft version reviewed ({@code STAGE_CONFLICT}
 * otherwise), full data validation and published-reference checks. Only when
 * every check passes is the draft snapshot copied atomically; any failure
 * leaves the previous published version untouched (no half-published state).
 *
 * <h3>Reference policy</h3>
 * On publish, a reference to a missing entry yields {@code REFERENCE_TARGET_NOT_FOUND};
 * a reference to an existing but unpublished entry yields
 * {@code REFERENCE_TARGET_NOT_PUBLISHED}; a wrong-typed reference yields
 * {@code FIELD_VALIDATION_FAILED}. Unpublishing or deleting a referenced
 * published entry is blocked by default ({@code REFERENCE_INTEGRITY_VIOLATION});
 * with {@code CASCADE_UNPUBLISH} the entry and the transitive closure of its
 * published referrers are unpublished together.
 *
 * <h3>Locking</h3>
 * All mutations of the published-reference index hold {@link #indexLock}, which
 * is always acquired <em>before</em> any per-entry lock; cascade operations
 * then take every affected entry lock in ascending id order with a timeout.
 * Draft-only edits take a single per-entry lock. This fixed ordering prevents
 * lock-ordering deadlocks.
 */
@Service
public class EntryService {

    public static final String STRATEGY_BLOCK = "BLOCK";
    public static final String STRATEGY_CASCADE_UNPUBLISH = "CASCADE_UNPUBLISH";

    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(5);

    private final Repositories repositories;
    private final ContentTypeService contentTypeService;
    private final ValueValidator validator;
    private final ValueProjector projector;

    /** source entry id -> ids of entries referenced by its <em>published</em> snapshot. */
    private final Map<String, Set<String>> publishedOutgoing = new ConcurrentHashMap<>();

    /** Serializes structural (publish/unpublish/delete) reference-index mutations. */
    private final ReentrantLock indexLock = new ReentrantLock();

    public EntryService(Repositories repositories, ContentTypeService contentTypeService,
                        ValueValidator validator, ValueProjector projector) {
        this.repositories = repositories;
        this.contentTypeService = contentTypeService;
        this.validator = validator;
        this.projector = projector;
    }

    // ------------------------------------------------------------------ create

    public Entry create(EntryRequest request) {
        if (request == null || request.typeId() == null || request.typeId().isBlank()) {
            throw new IllegalArgumentException("typeId must not be blank");
        }
        ContentType type = contentTypeService.get(request.typeId());
        ObjectNode data = requireObjectData(request.data());
        validator.validateData(type, data);

        String id = UUID.randomUUID().toString();
        Entry entry = new Entry(id, type.id(), data.deepCopy(), Instant.now());
        repositories.entries().put(id, entry);
        return entry;
    }

    public Entry getEntry(String entryId) {
        return repositories.requireEntry(entryId);
    }

    public List<Entry> list(String typeId) {
        return repositories.entries().values().stream()
                .filter(e -> typeId == null || e.typeId().equals(typeId))
                .sorted(Comparator.comparing(Entry::id))
                .toList();
    }

    // --------------------------------------------------------------- draft edit

    public Entry updateDraft(String entryId, EntryRequest request) {
        if (request == null || request.expectVersion() == null) {
            throw new ContentModelException(ErrorCode.BAD_REQUEST,
                    "expectVersion is required for draft updates (optimistic concurrency)");
        }
        Entry entry = repositories.requireEntry(entryId);
        if (request.typeId() != null && !request.typeId().equals(entry.typeId())) {
            throw new ContentModelException(ErrorCode.BAD_REQUEST,
                    "entry '" + entryId + "' is of type '" + entry.typeId()
                            + "', not '" + request.typeId() + "'");
        }
        ContentType type = contentTypeService.get(entry.typeId());
        ObjectNode data = requireObjectData(request.data());

        ReentrantLock lock = repositories.lockFor(entryId);
        lock.lock();
        try {
            long current = entry.draftVersion();
            if (request.expectVersion() != current) {
                throw new ContentModelException(ErrorCode.DRAFT_VERSION_CONFLICT,
                        "draft version conflict on entry '" + entryId + "'",
                        List.of("expectedVersion=" + request.expectVersion(),
                                "actualVersion=" + current,
                                "re-read the latest draft and reapply your changes"));
            }
            validator.validateData(type, data);
            entry.updateDraft(data.deepCopy(), Instant.now());
            return entry;
        } finally {
            lock.unlock();
        }
    }

    // ----------------------------------------------------------------- publish

    public Entry publish(String entryId, PublishRequest request) {
        if (request == null || request.expectDraftVersion() == null) {
            throw new ContentModelException(ErrorCode.BAD_REQUEST,
                    "expectDraftVersion is required so publication is pinned to one definite draft");
        }
        Entry entry = repositories.requireEntry(entryId);

        indexLock.lock();
        ReentrantLock ownLock = repositories.lockFor(entryId);
        ownLock.lock();
        try {
            long currentDraft = entry.draftVersion();
            if (request.expectDraftVersion() != currentDraft) {
                throw new ContentModelException(ErrorCode.STAGE_CONFLICT,
                        "entry '" + entryId + "' draft moved since publication was requested",
                        List.of("expectedDraftVersion=" + request.expectDraftVersion(),
                                "actualDraftVersion=" + currentDraft,
                                "re-read the draft and request publication again"));
            }
            ContentType type = contentTypeService.get(entry.typeId());
            // 1. structural validation against the current type definition
            validator.validateData(type, entry.draft());
            // 2. published-reference integrity (distinct failure reasons)
            checkReferencesForPublish(type, entry.draft());
            // 3. all checks passed -> atomic snapshot publication
            ObjectNode snapshot = entry.draft().deepCopy();
            entry.publish(snapshot, currentDraft, Instant.now());
            rebuildOutgoingIndex(entry.id(), type, entry.published());
            return entry;
        } finally {
            ownLock.unlock();
            indexLock.unlock();
        }
    }

    private void checkReferencesForPublish(ContentType type, ObjectNode data) {
        Map<String, List<String>> refs = validator.extractReferences(type, data);
        List<String> notFound = new ArrayList<>();
        List<String> notPublished = new ArrayList<>();
        List<String> typeMismatch = new ArrayList<>();

        for (Map.Entry<String, List<String>> e : refs.entrySet()) {
            FieldDefinition field = type.field(e.getKey());
            for (String targetId : e.getValue()) {
                Entry target = repositories.entries().get(targetId);
                if (target == null) {
                    notFound.add("REFERENCE_TARGET_NOT_FOUND: field '" + field.id()
                            + "' references entry '" + targetId + "' which does not exist");
                    continue;
                }
                if (!target.typeId().equals(field.referencedTypeId())) {
                    typeMismatch.add("FIELD_VALIDATION_FAILED: field '" + field.id()
                            + "' references entry '" + targetId + "' of type '" + target.typeId()
                            + "', expected type '" + field.referencedTypeId() + "'");
                }
                if (!target.isPublished()) {
                    notPublished.add("REFERENCE_TARGET_NOT_PUBLISHED: field '" + field.id()
                            + "' references entry '" + targetId
                            + "' which exists but has no published version");
                }
            }
        }

        List<String> all = new ArrayList<>();
        all.addAll(notFound);
        all.addAll(notPublished);
        all.addAll(typeMismatch);
        if (!all.isEmpty()) {
            ErrorCode code = !notFound.isEmpty() ? ErrorCode.REFERENCE_TARGET_NOT_FOUND
                    : !notPublished.isEmpty() ? ErrorCode.REFERENCE_TARGET_NOT_PUBLISHED
                    : ErrorCode.FIELD_VALIDATION_FAILED;
            throw new ContentModelException(code,
                    "publication rejected: reference integrity check failed", all);
        }
    }

    // ------------------------------------------------------------- unpublish

    public Entry unpublish(String entryId, UnpublishRequest request) {
        String strategy = normalizeStrategy(request == null ? null : request.referenceStrategy());
        Entry entry = repositories.requireEntry(entryId);

        List<ReentrantLock> acquired = new ArrayList<>();
        indexLock.lock();
        try {
            if (!entry.isPublished()) {
                throw new ContentModelException(ErrorCode.ENTRY_NOT_PUBLISHED,
                        "entry '" + entryId + "' is not published");
            }
            Set<String> closure = publishedReferrerClosure(entryId);
            if (STRATEGY_BLOCK.equals(strategy) && !closure.isEmpty()) {
                throwReferenceIntegrity(entryId, closure);
            }
            acquireAffected(entryId, closure, acquired);

            // re-check under all locks: nothing in the closure was unpublished meanwhile
            if (!entry.isPublished()) {
                throw new ContentModelException(ErrorCode.ENTRY_NOT_PUBLISHED,
                        "entry '" + entryId + "' is not published");
            }
            Instant now = Instant.now();
            entry.unpublish(now);
            publishedOutgoing.remove(entryId);
            for (String otherId : closure) {
                Entry other = repositories.entries().get(otherId);
                if (other != null && other.isPublished()) {
                    other.unpublish(now);
                    publishedOutgoing.remove(otherId);
                }
            }
            return entry;
        } finally {
            releaseInReverse(acquired);
            indexLock.unlock();
        }
    }

    // ------------------------------------------------------------------ delete

    public void delete(String entryId, DeleteEntryRequest request) {
        String strategy = normalizeStrategy(request == null ? null : request.referenceStrategy());
        Entry entry = repositories.requireEntry(entryId);

        List<ReentrantLock> acquired = new ArrayList<>();
        indexLock.lock();
        try {
            Set<String> closure = entry.isPublished() ? publishedReferrerClosure(entryId) : Set.of();
            if (STRATEGY_BLOCK.equals(strategy) && !closure.isEmpty()) {
                throwReferenceIntegrity(entryId, closure);
            }
            acquireAffected(entryId, closure, acquired);

            if (repositories.entries().get(entryId) == null) {
                throw new ContentModelException(ErrorCode.ENTRY_NOT_FOUND,
                        "entry '" + entryId + "' no longer exists");
            }
            repositories.entries().remove(entryId);
            publishedOutgoing.remove(entryId);
            Instant now = Instant.now();
            for (String otherId : closure) {
                Entry other = repositories.entries().get(otherId);
                if (other != null && other.isPublished()) {
                    other.unpublish(now);
                    publishedOutgoing.remove(otherId);
                }
            }
        } finally {
            releaseInReverse(acquired);
            indexLock.unlock();
        }
    }

    // -------------------------------------------------------------------- reads

    public EntryView readDraft(String entryId, String language, List<String> fallbackLanguages) {
        Entry entry = repositories.requireEntry(entryId);
        ContentType type = contentTypeService.get(entry.typeId());
        Map<String, ResolvedValue> fields =
                projector.project(type, entry.draft(), language, fallbackLanguages);
        return new EntryView(entry.id(), entry.typeId(), type.version(), "draft",
                entry.draftVersion(), entry.publishedVersion(), entry.publishedBaseDraftVersion(),
                language, fallbackLanguages == null ? List.of() : fallbackLanguages, fields);
    }

    public EntryView readPublished(String entryId, String language, List<String> fallbackLanguages) {
        Entry entry = repositories.requireEntry(entryId);
        if (!entry.isPublished()) {
            throw new ContentModelException(ErrorCode.ENTRY_NOT_PUBLISHED,
                    "entry '" + entryId + "' has no published version");
        }
        ContentType type = contentTypeService.get(entry.typeId());
        Map<String, ResolvedValue> fields =
                projector.project(type, entry.published(), language, fallbackLanguages);
        return new EntryView(entry.id(), entry.typeId(), type.version(), "published",
                entry.draftVersion(), entry.publishedVersion(), entry.publishedBaseDraftVersion(),
                language, fallbackLanguages == null ? List.of() : fallbackLanguages, fields);
    }

    // ------------------------------------------------------------- index helpers

    private void rebuildOutgoingIndex(String sourceId, ContentType type, ObjectNode publishedData) {
        Set<String> targets = new LinkedHashSet<>();
        validator.extractReferences(type, publishedData).values()
                .forEach(targets::addAll);
        if (targets.isEmpty()) {
            publishedOutgoing.remove(sourceId);
        } else {
            publishedOutgoing.put(sourceId, targets);
        }
    }

    /** Published entries that transitively reference {@code targetId}. */
    private Set<String> publishedReferrerClosure(String targetId) {
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(targetId);
        Set<String> visited = new HashSet<>();
        visited.add(targetId);
        while (!queue.isEmpty()) {
            String t = queue.poll();
            for (Map.Entry<String, Set<String>> e : publishedOutgoing.entrySet()) {
                if (e.getValue().contains(t) && visited.add(e.getKey())) {
                    Entry referrer = repositories.entries().get(e.getKey());
                    if (referrer != null && referrer.isPublished()) {
                        closure.add(e.getKey());
                        queue.add(e.getKey());
                    }
                }
            }
        }
        return closure;
    }

    /** Acquire the current entry lock and every affected lock in ascending id order. */
    private void acquireAffected(String selfId, Set<String> closure, List<ReentrantLock> acquired) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(selfId);
        ids.addAll(closure);
        List<String> ordered = ids.stream().sorted().toList();
        for (String id : ordered) {
            ReentrantLock lock = repositories.lockFor(id);
            try {
                if (!lock.tryLock(LOCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new ContentModelException(ErrorCode.STAGE_CONFLICT,
                            "entry '" + id + "' is busy with another concurrent operation; retry shortly");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContentModelException(ErrorCode.STAGE_CONFLICT,
                        "interrupted while waiting to lock entry '" + id + "'");
            }
            acquired.add(lock);
        }
    }

    private void releaseInReverse(List<ReentrantLock> acquired) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            acquired.get(i).unlock();
        }
    }

    private void throwReferenceIntegrity(String entryId, Set<String> referrers) {
        List<String> details = new ArrayList<>();
        details.add("entry '" + entryId + "' is referenced by " + referrers.size()
                + " published entr(y/ies): " + new ArrayList<>(referrers));
        details.add("unpublish or delete those referrers first, or use referenceStrategy="
                + STRATEGY_CASCADE_UNPUBLISH);
        throw new ContentModelException(ErrorCode.REFERENCE_INTEGRITY_VIOLATION,
                "operation blocked: published entries reference entry '" + entryId + "'", details);
    }

    private String normalizeStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) return STRATEGY_BLOCK;
        String s = strategy.trim().toUpperCase();
        if (!Set.of(STRATEGY_BLOCK, STRATEGY_CASCADE_UNPUBLISH).contains(s)) {
            throw new ContentModelException(ErrorCode.BAD_REQUEST,
                    "unknown referenceStrategy '" + strategy + "'",
                    List.of("supported: " + STRATEGY_BLOCK + ", " + STRATEGY_CASCADE_UNPUBLISH));
        }
        return s;
    }

    private ObjectNode requireObjectData(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new ContentModelException(ErrorCode.BAD_REQUEST,
                    "entry data must be a JSON object keyed by field id");
        }
        return (ObjectNode) data;
    }

}

