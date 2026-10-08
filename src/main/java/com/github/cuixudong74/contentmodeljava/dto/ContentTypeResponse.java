package com.github.cuixudong74.contentmodeljava.dto;

import com.github.cuixudong74.contentmodeljava.model.FieldDefinition;

import java.util.List;

public record ContentTypeResponse(String id, String displayName, long version, List<FieldDefinition> fields) {
}
