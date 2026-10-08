package com.github.cuixudong74.contentmodeljava.service;

import com.github.cuixudong74.contentmodeljava.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.dto.FieldDefinitionRequest;
import com.github.cuixudong74.contentmodeljava.error.ContentModelException;
import com.github.cuixudong74.contentmodeljava.error.ErrorCode;
import com.github.cuixudong74.contentmodeljava.model.ContentType;
import com.github.cuixudong74.contentmodeljava.model.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.model.FieldType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Content type registry and its (safe) evolution rules.
 *
 * <p>Accepted evolution while entries already exist:
 * <ul>
 *     <li>add an optional field, or a field carrying a default value,</li>
 *     <li>tighten / relax scalar validation ranges and allowed values,</li>
 *     <li>remove a field (stored values become orphaned but are untouched).</li>
 * </ul>
 * Rejected evolution: adding a required field without a default, or flipping
 * an existing optional field to required without a default — both would make
 * already-persisted entries structurally invalid.
 */
@Service
public class ContentTypeService {

    private final Repositories repositories;
    private final ValueValidator validator;

    public ContentTypeService(Repositories repositories, ValueValidator validator) {
        this.repositories = repositories;
        this.validator = validator;
    }

    public ContentType create(ContentTypeRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("content type id must not be blank");
        }
        if (repositories.types().putIfAbsent(request.id(),
                new ContentType(request.id(), request.displayName(), 0)) != null) {
            throw new ContentModelException(ErrorCode.TYPE_ALREADY_EXISTS,
                    "content type '" + request.id() + "' already exists");
        }
        ContentType type = repositories.types().get(request.id());
        Set<String> seen = new HashSet<>();
        List<String> duplicateErrors = new ArrayList<>();
        if (request.fields() != null) {
            for (FieldDefinitionRequest fr : request.fields()) {
                FieldDefinition field = toModel(fr);
                if (!seen.add(field.id())) {
                    duplicateErrors.add("duplicate field id '" + field.id() + "'");
                    continue;
                }
                validator.validateDefinition(field);
                type.addField(field);
            }
        }
        if (!duplicateErrors.isEmpty()) {
            repositories.types().remove(request.id());
            throw new ContentModelException(ErrorCode.FIELD_DUPLICATE_ID,
                    "duplicate field ids in content type '" + request.id() + "'", duplicateErrors);
        }
        type.bumpVersion();
        return type;
    }

    public ContentType get(String typeId) {
        return repositories.requireType(typeId);
    }

    public List<ContentType> list() {
        return new ArrayList<>(repositories.types().values());
    }

    public ContentType addField(String typeId, FieldDefinitionRequest request) {
        ContentType type = repositories.requireType(typeId);
        FieldDefinition field = toModel(request);
        if (type.hasField(field.id())) {
            throw new ContentModelException(ErrorCode.FIELD_DUPLICATE_ID,
                    "field '" + field.id() + "' already exists in content type '" + typeId + "'");
        }
        validator.validateDefinition(field);
        if (hasEntries(typeId) && !field.safeToAddToExistingType()) {
            throw new ContentModelException(ErrorCode.FIELD_EVOLUTION_UNSAFE,
                    "cannot add required field '" + field.id()
                            + "' without a default value while entries of type '" + typeId + "' exist",
                    List.of("make the field optional", "provide a defaultValue",
                            "delete existing entries of this type first"));
        }
        type.addField(field);
        type.bumpVersion();
        return type;
    }

    public ContentType updateField(String typeId, String fieldId, FieldDefinitionRequest request) {
        ContentType type = repositories.requireType(typeId);
        FieldDefinition existing = type.field(fieldId);
        if (existing == null) {
            throw new ContentModelException(ErrorCode.FIELD_NOT_FOUND,
                    "field '" + fieldId + "' does not exist in content type '" + typeId + "'");
        }
        FieldDefinition updated = toModel(request);
        if (!updated.id().equals(fieldId)) {
            throw new ContentModelException(ErrorCode.BAD_REQUEST,
                    "field id in path ('" + fieldId + "') and body ('" + updated.id() + "') must match");
        }
        validator.validateDefinition(updated);
        if (hasEntries(typeId)
                && !existing.required() && updated.required()
                && updated.defaultValue() == null && existing.defaultValue() == null) {
            throw new ContentModelException(ErrorCode.FIELD_EVOLUTION_UNSAFE,
                    "cannot make field '" + fieldId
                            + "' required without a default value while entries exist",
                    List.of("keep the field optional", "provide a defaultValue"));
        }
        type.replaceField(updated);
        type.bumpVersion();
        return type;
    }

    public ContentType removeField(String typeId, String fieldId) {
        ContentType type = repositories.requireType(typeId);
        if (!type.hasField(fieldId)) {
            throw new ContentModelException(ErrorCode.FIELD_NOT_FOUND,
                    "field '" + fieldId + "' does not exist in content type '" + typeId + "'");
        }
        type.removeField(fieldId);
        type.bumpVersion();
        return type;
    }

    public boolean hasEntries(String typeId) {
        return repositories.entries().values().stream().anyMatch(e -> e.typeId().equals(typeId));
    }

    /** Convert a request DTO to an immutable field definition. */
    public static FieldDefinition toModel(FieldDefinitionRequest r) {
        if (r == null || r.id() == null || r.id().isBlank()) {
            throw new IllegalArgumentException("field id must not be blank");
        }
        FieldType type;
        try {
            type = FieldType.valueOf(FieldType.class, r.type() == null ? "" : r.type().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ContentModelException(ErrorCode.FIELD_INVALID_DEFINITION,
                    "field '" + r.id() + "' has unknown type '" + r.type() + "'",
                    List.of("supported types: STRING, INTEGER, NUMBER, BOOLEAN, DATE, REFERENCE, LOCALIZED_STRING"));
        }
        return new FieldDefinition(
                r.id(),
                r.name() == null ? r.id() : r.name(),
                type,
                Boolean.TRUE.equals(r.required()),
                Boolean.TRUE.equals(r.localized()),
                r.defaultValue(),
                r.maxLength(),
                r.min(),
                r.max(),
                r.allowedValues(),
                r.referencedTypeId());
    }
}
