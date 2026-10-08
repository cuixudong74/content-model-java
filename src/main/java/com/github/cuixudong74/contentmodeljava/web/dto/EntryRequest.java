package com.github.cuixudong74.contentmodeljava.web.dto;

import java.util.Map;

/**
 * Request body for creating an entry or replacing its draft values.
 *
 * @param expectedVersion current draft version the editor based its edit on; required on update
 * @param values          full replacement map of raw field values
 */
public record EntryRequest(Long expectedVersion, Map<String, Object> values) {
}
