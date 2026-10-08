package com.github.cuixudong74.contentmodeljava.exception;

/**
 * Stable error codes returned by the API. See docs/model.md for semantics.
 */
public enum ErrorCode {
    TYPE_NOT_FOUND,
    TYPE_ALREADY_EXISTS,
    FIELD_DUPLICATE,
    FIELD_INVALID_DEFINITION,
    FIELD_EVOLUTION_UNSUPPORTED,
    ENTRY_NOT_FOUND,
    ENTRY_NOT_PUBLISHED,
    VERSION_CONFLICT,
    VALIDATION_FAILED,
    REFERENCE_TARGET_NOT_FOUND,
    REFERENCE_TARGET_NOT_PUBLISHED,
    REFERENCE_INTEGRITY_VIOLATION
}
