package com.github.cuixudong74.contentmodeljava.exception;

/**
 * Single business exception type carrying a stable {@link ErrorCode} and optional details.
 */
public class ContentModelException extends RuntimeException {

    private final ErrorCode code;
    private final transient Object details;

    public ContentModelException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public ContentModelException(ErrorCode code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }
}
