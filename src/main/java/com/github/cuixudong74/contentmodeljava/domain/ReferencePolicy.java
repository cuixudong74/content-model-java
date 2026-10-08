package com.github.cuixudong74.contentmodeljava.domain;

/**
 * Policy applied when a referenced entry is unpublished or deleted.
 */
public enum ReferencePolicy {
    /** Block the operation while a published referrer still points at the target. */
    RESTRICT,
    /** Cascade the unpublish/delete to every published referrer. */
    CASCADE
}
