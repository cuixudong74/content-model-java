package com.github.cuixudong74.contentmodeljava.error;

/**
 * Stable, machine-readable error codes for the content model service.
 */
public enum ErrorCode {

    // ---- content type / field definition ----
    TYPE_NOT_FOUND(404),
    TYPE_ALREADY_EXISTS(409),
    FIELD_DUPLICATE_ID(422),
    FIELD_INVALID_DEFINITION(422),
    FIELD_NOT_FOUND(404),
    FIELD_EVOLUTION_UNSAFE(422),

    // ---- entries ----
    ENTRY_NOT_FOUND(404),
    ENTRY_ALREADY_EXISTS(409),
    ENTRY_NOT_PUBLISHED(409),
    FIELD_VALIDATION_FAILED(422),

    // ---- draft / publish ----
    STAGE_CONFLICT(409),
    DRAFT_VERSION_CONFLICT(409),

    // ---- references ----
    REFERENCE_TARGET_NOT_FOUND(422),
    REFERENCE_TARGET_NOT_PUBLISHED(422),
    REFERENCE_INTEGRITY_VIOLATION(409),

    // ---- generic ----
    BAD_REQUEST(400),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    ErrorCode(int httpStatus) { this.httpStatus = httpStatus; }

    public int httpStatus() { return httpStatus; }
}
