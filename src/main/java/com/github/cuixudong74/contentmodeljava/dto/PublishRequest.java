package com.github.cuixudong74.contentmodeljava.dto;

/**
 * Publish must be based on a definite draft version. The caller supplies the
 * draft version it reviewed; publication is rejected with STAGE_CONFLICT if
 * the draft moved on in the meantime.
 */
public record PublishRequest(Long expectDraftVersion) {
}
