package com.github.cuixudong74.contentmodeljava.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Request body for creating or replacing a field definition.
 */
public record FieldDefinitionRequest(
        String id,
        String name,
        String type,
        Boolean required,
        Boolean localized,
        JsonNode defaultValue,
        Integer maxLength,
        Double min,
        Double max,
        List<String> allowedValues,
        String referencedTypeId
) {
}
