package com.github.cuixudong74.contentmodeljava.web.dto;

import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;

import java.util.List;

/**
 * Request body for creating or evolving a content type.
 *
 * @param id          content type identifier; required on create (ignored if it duplicates the path id)
 * @param displayName optional human-readable name
 * @param fields      complete field set on create; on evolve, the added/changed fields
 */
public record ContentTypeRequest(String id, String displayName, List<FieldDefinition> fields) {
}
