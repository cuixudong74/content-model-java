package com.github.cuixudong74.contentmodeljava.service;

import com.github.cuixudong74.contentmodeljava.domain.ContentType;
import com.github.cuixudong74.contentmodeljava.domain.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.domain.FieldType;
import com.github.cuixudong74.contentmodeljava.web.dto.FieldValueView;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects a stored raw value map onto the <em>current</em> version of a content type schema.
 *
 * <p>Projection is read-only and never throws on legacy data:
 * <ul>
 *   <li>a field added after the entry was written is filled from its {@code defaultValue}
 *       (state DEFAULTED) or reported UNSET;</li>
 *   <li>a stored value that no longer satisfies tightened validation is reported UNSET rather
 *       than breaking the read (the invalid stored value is not exposed);</li>
 *   <li>localized strings resolve through the requested locale and an explicit fallback chain;
 *       served fallback values carry {@code fallback=true} and the locale actually used.</li>
 * </ul>
 */
@Service
public class ProjectionService {

    /** Project without locale resolution: localized fields return their raw translation map. */
    public Map<String, FieldValueView> project(ContentType type, Map<String, Object> rawValues) {
        return projectInternal(type, rawValues, null, List.of());
    }

    /** Project values for a requested locale with an ordered fallback chain. */
    public Map<String, FieldValueView> project(ContentType type, Map<String, Object> rawValues,
                                               String locale, List<String> fallbackLocales) {
        return projectInternal(type, rawValues, locale,
                fallbackLocales == null ? List.of() : fallbackLocales);
    }

    private Map<String, FieldValueView> projectInternal(ContentType type, Map<String, Object> raw,
                                                        String locale, List<String> fallbacks) {
        Map<String, FieldValueView> result = new LinkedHashMap<>();
        Map<String, Object> values = raw == null ? Map.of() : raw;
        synchronized (type) {
            for (FieldDefinition field : type.fieldList()) {
                Object stored = values.get(field.key());
                if (field.localized() && field.type() == FieldType.STRING) {
                    result.put(field.key(),
                            projectLocalized(field, stored, locale, fallbacks));
                } else {
                    result.put(field.key(), projectScalar(field, stored));
                }
            }
        }
        return result;
    }

    private FieldValueView projectScalar(FieldDefinition field, Object stored) {
        if (stored == null) {
            return field.defaultValue() == null
                    ? FieldValueView.unset()
                    : FieldValueView.defaulted(field.defaultValue());
        }
        if (!ValueValidator.isShapeValid(field, stored)) {
            // Legacy data predating tightened constraints: never break the read.
            return FieldValueView.unset();
        }
        return FieldValueView.present(stored);
    }

    @SuppressWarnings("unchecked")
    private FieldValueView projectLocalized(FieldDefinition field, Object stored,
                                            String requestedLocale, List<String> fallbacks) {
        Map<String, Object> translations;
        if (stored instanceof Map<?, ?> map) {
            translations = (Map<String, Object>) map;
        } else if (stored == null) {
            translations = Map.of();
        } else {
            // Legacy non-map value for a now-localized field cannot be interpreted per locale.
            return FieldValueView.unset();
        }

        List<String> chain = new ArrayList<>();
        if (requestedLocale != null && !requestedLocale.isBlank()) {
            chain.add(requestedLocale);
        }
        chain.addAll(fallbacks);

        for (String loc : chain) {
            Object value = translations.get(loc);
            if (value instanceof String s && ValueValidator.isStringInRange(field, s)) {
                boolean fallback = !loc.equals(requestedLocale);
                return new FieldValueView("PRESENT", s, loc, fallback);
            }
        }

        if (field.defaultValue() instanceof String d) {
            String loc = requestedLocale != null ? requestedLocale : null;
            return new FieldValueView("DEFAULTED", d, loc, false);
        }
        return FieldValueView.unset();
    }
}
