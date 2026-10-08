package com.github.cuixudong74.contentmodeljava.error;

import java.util.Collections;
import java.util.List;

/**
 * Business error carrying a stable {@link ErrorCode} and a human-readable
 * message. Validation errors may carry multiple field-level details.
 */
public class ContentModelException extends RuntimeException {

    private final ErrorCode code;
    private final List<String> details;

    public ContentModelException(ErrorCode code, String message) {
        this(code, message, Collections.emptyList());
    }

    public ContentModelException(ErrorCode code, String message, List<String> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public ErrorCode code() { return code; }
    public List<String> details() { return details; }
}
