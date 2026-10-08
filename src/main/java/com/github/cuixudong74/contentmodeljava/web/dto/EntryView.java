package com.github.cuixudong74.contentmodeljava.web.dto;

import java.util.Map;

/**
 * View of an entry at a given stage (DRAFT or PUBLISHED), projected onto the current schema.
 */
public record EntryView(String id, String typeId, String stage, long version, long schemaVersion,
                        Map<String, FieldValueView> values) {
}
