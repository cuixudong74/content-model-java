package com.github.cuixudong74.contentmodeljava.store;

import com.github.cuixudong74.contentmodeljava.domain.Entry;
import com.github.cuixudong74.contentmodeljava.exception.ContentModelException;
import com.github.cuixudong74.contentmodeljava.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store of content entries. A dedicated per-id lock object serializes mutating
 * operations on one entry (draft replace / publish / unpublish / delete) so optimistic
 * concurrency checks are atomic. Locks are intentionally retained after deletion: a removed
 * id must never map to a different lock object while an operation is in flight.
 */
@Component
public class EntryStore {

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    public Optional<Entry> find(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    public Entry get(String id) {
        return find(id).orElseThrow(() -> new ContentModelException(
                ErrorCode.ENTRY_NOT_FOUND, "entry '" + id + "' does not exist"));
    }

    public Entry create(String typeId) {
        String id = "entry-" + idSequence.incrementAndGet();
        Entry entry = new Entry(id, typeId);
        Entry previous = entries.putIfAbsent(id, entry);
        if (previous != null) {
            // Practically unreachable with a monotonic sequence.
            throw new ContentModelException(ErrorCode.VERSION_CONFLICT,
                    "entry id collision: " + id);
        }
        return entry;
    }

    public void delete(String id) {
        entries.remove(id);
    }

    public List<Entry> findByType(String typeId) {
        return entries.values().stream().filter(e -> e.getTypeId().equals(typeId)).toList();
    }

    public List<Entry> findAll() {
        return List.copyOf(entries.values());
    }

    /** @return the stable monitor used to serialize mutations of one entry. */
    public Object lockFor(String id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }
}
