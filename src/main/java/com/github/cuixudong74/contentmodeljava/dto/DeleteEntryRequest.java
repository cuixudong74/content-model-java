package com.github.cuixudong74.contentmodeljava.dto;

/**
 * @param referenceStrategy BLOCK (default) or CASCADE_UNPUBLISH when the
 *                          entry is referenced by other published entries
 */
public record DeleteEntryRequest(String referenceStrategy) {
}
