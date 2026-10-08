package com.github.cuixudong74.contentmodeljava.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.cuixudong74.contentmodeljava.model.ContentType;
import com.github.cuixudong74.contentmodeljava.model.FieldDefinition;
import com.github.cuixudong74.contentmodeljava.model.FieldType;
import com.github.cuixudong74.contentmodeljava.model.ResolvedValue;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects a raw entry snapshot against the current content type definition.
 *
 * <p>Projection semantics:
 * <ul>
 *     <li>fields removed from the type are hidden (stored values stay on disk),</li>
 *     <li>fields added after the snapshot was taken are projected from their
 *     declared {@code defaultValue} ({@link ResolvedValue.ValueState#DEFAULT})
 *     or reported {@link ResolvedValue.ValueState#UNSET},</li>
 *     <li>{@code LOCALIZED_STRING} fields resolve requested language first,
 *     then the explicit fallback chain, then any other translation; values
 *     obtained outside the requested language are marked FALLBACK with the
 *     actual source language.</li>
 * </ul>
 * Because every read goes through projection, evolving a content type never
 * breaks old entries.
 */
@Component
public class ValueProjector {

    public Map<String, ResolvedValue> project(ContentType type, ObjectNode snapshot,
                                              String language, List<String> fallbackLanguages) {
        Map<String, ResolvedValue> out = new LinkedHashMap<>();
        List<String> chain = fallbackLanguages == null ? List.of() : fallbackLanguages;

        for (FieldDefinition field : type.fields()) {
            JsonNode raw = snapshot == null ? null : snapshot.get(field.id());
            if (raw == null || raw.isNull()) {
                out.put(field.id(), missingField(field));
                continue;
            }

            if (field.type() == FieldType.LOCALIZED_STRING && raw.isObject()) {
                out.put(field.id(), resolveLocalized(field, raw, language, chain));
            } else {
                out.put(field.id(), new ResolvedValue(toJava(raw), ResolvedValue.ValueState.PRESENT, null));
            }
        }
        return out;
    }

    private ResolvedValue missingField(FieldDefinition field) {
        if (field.defaultValue() instanceof JsonNode d && !d.isNull()) {
            if (field.type() == FieldType.LOCALIZED_STRING && d.isObject() && d.size() > 0) {
                var first = d.fields().next();
                return new ResolvedValue(first.getValue().asText(),
                        ResolvedValue.ValueState.DEFAULT, first.getKey());
            }
            return new ResolvedValue(toJava(d), ResolvedValue.ValueState.DEFAULT, null);
        }
        return new ResolvedValue(null, ResolvedValue.ValueState.UNSET, null);
    }

    private ResolvedValue resolveLocalized(FieldDefinition field, JsonNode raw,
                                           String requested, List<String> fallbackChain) {
        // No language preference expressed: hand back the whole translation map
        // verbatim, marked PRESENT, so callers can choose a language themselves.
        if (requested == null && (fallbackChain == null || fallbackChain.isEmpty())) {
            return new ResolvedValue(toJava(raw), ResolvedValue.ValueState.PRESENT, null);
        }
        if (requested != null) {
            JsonNode exact = raw.get(requested);
            if (exact != null && exact.isTextual()) {
                return new ResolvedValue(exact.asText(), ResolvedValue.ValueState.PRESENT, requested);
            }
            for (String fb : fallbackChain) {
                JsonNode candidate = raw.get(fb);
                if (candidate != null && candidate.isTextual()) {
                    return new ResolvedValue(candidate.asText(),
                            ResolvedValue.ValueState.FALLBACK, fb);
                }
            }
        } else if (!fallbackChain.isEmpty()) {
            for (String fb : fallbackChain) {
                JsonNode candidate = raw.get(fb);
                if (candidate != null && candidate.isTextual()) {
                    return new ResolvedValue(candidate.asText(),
                            ResolvedValue.ValueState.FALLBACK, fb);
                }
            }
        }
        // Last resort: keep a deterministic result by returning any available translation,
        // still marked as FALLBACK so callers can tell it apart from an original value.
        if (raw.size() > 0) {
            List<String> keys = new ArrayList<>();
            raw.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            String firstKey = keys.get(0);
            return new ResolvedValue(raw.get(firstKey).asText(),
                    ResolvedValue.ValueState.FALLBACK, firstKey);
        }
        return missingField(field);
    }

    private Object toJava(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isNumber()) return node.asDouble();
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(e -> list.add(toJava(e)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), toJava(e.getValue())));
            return map;
        }
        return node.asText();
    }
}
