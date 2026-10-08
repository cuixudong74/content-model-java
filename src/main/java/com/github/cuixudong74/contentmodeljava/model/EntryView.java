package com.github.cuixudong74.contentmodeljava.model;

import java.util.Map;

/**
 * A read projection of an entry stage (draft or published) against the
 * <em>current</em> content type definition. Fields added after the snapshot
 * was taken are projected as DEFAULT/UNSET, and removed fields are omitted.
 *
 * @param id                    entry id
 * @param typeId                content type id
 * @param typeVersion           content type version this projection was built against
 * @param stage                 "draft" or "published"
 * @param draftVersion          draft version (null for a published projection without draft context)
 * @param publishedVersion      published version, 0 when never published
 * @param baseDraftVersion      draft version the published snapshot was taken from, if published
 * @param requestedLanguage     language requested for the projection, if any
 * @param fallbackLanguages     fallback chain applied, if any
 * @param fields                per-field resolved values
 */
public record EntryView(String id, String typeId, long typeVersion, String stage,
                        Long draftVersion, long publishedVersion, Long baseDraftVersion,
                        String requestedLanguage, java.util.List<String> fallbackLanguages,
                        Map<String, ResolvedValue> fields) {
}
