package com.github.cuixudong74.contentmodeljava.service;

import com.github.cuixudong74.contentmodeljava.domain.ContentType;
import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.exception.ContentModelException;
import com.github.cuixudong74.contentmodeljava.exception.ErrorCode;
import com.github.cuixudong74.contentmodeljava.store.ContentTypeStore;
import com.github.cuixudong74.contentmodeljava.web.dto.ContentTypeRequest;
import com.github.cuixudong74.contentmodeljava.web.dto.ContentTypeView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CRUD and safe evolution of content type definitions.
 *
 * <p>Evolution is intentionally restricted to changes that never break already stored data:
 * adding optional fields, adding fields with a default value, and widening/tightening
 * validation ranges. Removing fields, changing a field's type, making an existing field
 * required without a default and converting between scalar/reference and localized flags are
 * rejected with FIELD_EVOLUTION_UNSUPPORTED.
 */
@Service
public class ContentTypeService {

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    private final ContentTypeStore store;
    private final FieldDefinitionValidator fieldValidator;

    public ContentTypeService(ContentTypeStore store, FieldDefinitionValidator fieldValidator) {
        this.store = store;
        this.fieldValidator = fieldValidator;
    }

    public ContentTypeView create(ContentTypeRequest request) {
        validateRequestShape(request);
        String id = request.id();
        if (id == null || id.isBlank()) {
            throw new ContentModelException(ErrorCode.VALIDATION_FAILED,
                    "content type id is required");
        }
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new ContentModelException(ErrorCode.VALIDATION_FAILED,
                    "content type id '" + id + "' must match [A-Za-z][A-Za-z0-9_-]*");
        }
        if (store.find(id).isPresent()) {
            throw new ContentModelException(ErrorCode.TYPE_ALREADY_EXISTS,
                    "content type '" + id + "' already exists");
        }
        List<FieldDefinition> fields = validateFieldSet(request.fields());

        synchronized (store) {
            // Re-check under the structural lock to serialize concurrent creates.
            if (store.find(id).isPresent()) {
                throw new ContentModelException(ErrorCode.TYPE_ALREADY_EXISTS,
                        "content type '" + id + "' already exists");
            }
            ContentType type = new ContentType(id,
                    request.displayName() == null ? id : request.displayName(), 1L);
            fields.forEach(type::putField);
            store.save(type);
            return toView(type);
        }
    }

    public ContentTypeView get(String id) {
        return toView(store.get(id));
    }

    public List<ContentTypeView> list() {
        return store.findAll().stream().map(this::toView).toList();
    }

    /**
     * Apply a safe evolution:
     * <ol>
     *   <li>new fields must be optional OR carry a default value,</li>
     *   <li>existing fields may only change in their validation range / required flag when a
     *       default is provided (type, key, refType and localized flag are immutable),</li>
     *   <li>fields omitted from the request are retained unchanged (explicit removal is rejected
     *       on the client side by sending the full intended set; removal itself is never allowed).</li>
     * </ol>
     */
    public ContentTypeView evolve(String id, ContentTypeRequest request) {
        validateRequestShape(request);
        ContentType type = store.get(id);
        List<FieldDefinition> incoming = validateFieldSet(request.fields());
        synchronized (store) {
            ContentType locked = store.get(id);
            List<String> evolutionErrors = new ArrayList<>();
            Map<String, FieldDefinition> current = locked.getFields();
            for (FieldDefinition next : incoming) {
                FieldDefinition prev = current.get(next.key());
                if (prev == null) {
                    if (next.required() && next.defaultValue() == null) {
                        evolutionErrors.add("new required field '" + next.key()
                                + "' must provide a defaultValue so existing entries stay readable");
                    }
                } else {
                    checkImmutable(prev, next, evolutionErrors);
                }
            }
            if (!evolutionErrors.isEmpty()) {
                throw new ContentModelException(ErrorCode.FIELD_EVOLUTION_UNSUPPORTED,
                        "content type evolution would break existing entries", evolutionErrors);
            }
            incoming.forEach(locked::putField);
            locked.bumpVersion();
            return toView(locked);
        }
    }

    private void checkImmutable(FieldDefinition prev, FieldDefinition next, List<String> errors) {
        String key = prev.key();
        if (prev.type() != next.type()) {
            errors.add("field '" + key + "': changing type from " + prev.type() + " to "
                    + next.type() + " is not supported");
        }
        if (prev.localized() != next.localized()) {
            errors.add("field '" + key + "': changing localized flag is not supported");
        }
        if (prev.type() == com.github.cuixudong74.contentmodeljava.domain.FieldType.REFERENCE
                && !java.util.Objects.equals(prev.refType(), next.refType())) {
            errors.add("field '" + key + "': changing refType is not supported");
        }
        if (next.required() && !prev.required() && next.defaultValue() == null) {
            errors.add("field '" + key
                    + "': making an existing field required requires a defaultValue");
        }
    }

    private void validateRequestShape(ContentTypeRequest request) {
        if (request == null) {
            throw new ContentModelException(ErrorCode.VALIDATION_FAILED,
                    "request body is required");
        }
    }

    /**
     * Validate each definition and reject duplicate keys within the same request.
     */
    private List<FieldDefinition> validateFieldSet(List<FieldDefinition> fields) {
        List<FieldDefinition> safe = fields == null ? List.of() : fields;
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FieldDefinition field : safe) {
            if (field == null) {
                errors.add("field entries must not be null");
                continue;
            }
            if (!seen.add(field.key())) {
                throw new ContentModelException(ErrorCode.FIELD_DUPLICATE,
                        "duplicate field key '" + field.key() + "' within the same content type",
                        field.key());
            }
            errors.addAll(fieldValidator.validate(field));
        }
        if (!errors.isEmpty()) {
            throw new ContentModelException(ErrorCode.FIELD_INVALID_DEFINITION,
                    "field definitions are invalid", errors);
        }
        return new ArrayList<>(safe);
    }

    private ContentTypeView toView(ContentType type) {
        synchronized (type) {
            return new ContentTypeView(type.getId(), type.getDisplayName(), type.getVersion(),
                    type.fieldList());
        }
    }
}
