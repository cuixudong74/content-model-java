package com.github.cuixudong74.contentmodeljava.dto;

import java.util.List;

public record ContentTypeRequest(String id, String displayName, List<FieldDefinitionRequest> fields) {
}
