package com.github.cuixudong74.contentmodeljava.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body for creating an entry or replacing its draft data.
 *
 * @param data         field values keyed by field id
 * @param expectVersion required for draft updates: the caller's last seen draft
 *                     version; a mismatch is rejected as DRAFT_VERSION_CONFLICT
 */
public record EntryRequest(String typeId, JsonNode data, Long expectVersion) {
}
