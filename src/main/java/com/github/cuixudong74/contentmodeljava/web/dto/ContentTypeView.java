package com.github.cuixudong74.contentmodeljava.web.dto;

import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;

import java.util.List;

/**
 * View of a content type definition including its monotonic schema version.
 */
public record ContentTypeView(String id, String displayName, long version,
                              List<FieldDefinition> fields) {
}
