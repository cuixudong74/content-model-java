package com.github.cuixudong74.contentmodeljava.web.dto;

/**
 * Publish must target one definite draft version to avoid half-published ambiguity.
 *
 * @param expectedDraftVersion draft version that is being published; may be null for first publish
 */
public record PublishRequest(Long expectedDraftVersion) {
}
