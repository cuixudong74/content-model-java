package com.github.cuixudong74.contentmodeljava.store;

import com.github.cuixudong74.contentmodeljava.domain.ContentType;
import com.github.cuixudong74.contentmodeljava.exception.ContentModelException;
import com.github.cuixudong74.contentmodeljava.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of content type definitions. Content types are mutated by services while
 * holding their own monitor; the map itself is concurrent to allow concurrent reads.
 */
@Component
public class ContentTypeStore {

    private final Map<String, ContentType> types = new ConcurrentHashMap<>();

    public Optional<ContentType> find(String id) {
        return Optional.ofNullable(types.get(id));
    }

    public ContentType get(String id) {
        return find(id).orElseThrow(() -> new ContentModelException(
                ErrorCode.TYPE_NOT_FOUND, "content type '" + id + "' does not exist"));
    }

    public void save(ContentType type) {
        types.put(type.getId(), type);
    }

    public List<ContentType> findAll() {
        return new ArrayList<>(types.values());
    }
}
