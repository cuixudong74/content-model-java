package com.github.cuixudong74.contentmodeljava.service;

import com.github.cuixudong74.contentmodeljava.model.ContentType;
import com.github.cuixudong74.contentmodeljava.model.Entry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory storage for content types and entries.
 *
 * <p>One {@link ConcurrentHashMap} per aggregate; entry-level mutations are
 * serialized per entry through a striped lock map, which makes optimistic
 * concurrency checks and atomic publication safe under concurrent editors.
 * Locks are reentrant so a cascade operation that includes the current entry
 * never deadlocks against itself.
 */
@Component
public class Repositories {

    private final Map<String, ContentType> types = new ConcurrentHashMap<>();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> entryLocks = new ConcurrentHashMap<>();

    public Map<String, ContentType> types() { return types; }

    public Map<String, Entry> entries() { return entries; }

    public Optional<ContentType> findType(String id) {
        return Optional.ofNullable(types.get(id));
    }

    public ContentType requireType(String id) {
        return findType(id).orElseThrow(() ->
                new com.github.cuixudong74.contentmodeljava.error.ContentModelException(
                        com.github.cuixudong74.contentmodeljava.error.ErrorCode.TYPE_NOT_FOUND,
                        "content type '" + id + "' does not exist"));
    }

    public Optional<Entry> findEntry(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    public Entry requireEntry(String id) {
        return findEntry(id).orElseThrow(() ->
                new com.github.cuixudong74.contentmodeljava.error.ContentModelException(
                        com.github.cuixudong74.contentmodeljava.error.ErrorCode.ENTRY_NOT_FOUND,
                        "entry '" + id + "' does not exist"));
    }

    /** Lock used to serialize all state-changing operations on one entry. */
    public ReentrantLock lockFor(String entryId) {
        return entryLocks.computeIfAbsent(entryId, k -> new ReentrantLock());
    }
}
