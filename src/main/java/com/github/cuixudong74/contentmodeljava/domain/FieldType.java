package com.github.cuixudong74.contentmodeljava.domain;

/**
 * Supported scalar/content field types.
 */
public enum FieldType {
    /** UTF-8 text, optionally length/pattern constrained. */
    STRING,
    /** 64-bit integral number, optionally range constrained. */
    INTEGER,
    /** Double precision floating point number, optionally range constrained. */
    NUMBER,
    /** Boolean flag. */
    BOOLEAN,
    /** ISO-8601 date-time string, e.g. 2026-10-07T10:00:00Z. */
    DATETIME,
    /** Reference to another content entry. Value stored as the referenced entry id. */
    REFERENCE
}
