package com.github.cuixudong74.contentmodeljava.dto;

import com.github.cuixudong74.contentmodeljava.model.ResolvedValue;

import java.util.List;
import java.util.Map;

/**
 * Projected entry read. Values are resolved against the current content type,
 * including default/unset projection and language fallback metadata.
 */
public record EntryResponse(String id, String typeId, long typeVersion, String stage,
                            Long draftVersion, long publishedVersion, Long baseDraftVersion,
                            String requestedLanguage, List<String> fallbackLanguages,
                            Map<String, ResolvedValue> fields) {
}
