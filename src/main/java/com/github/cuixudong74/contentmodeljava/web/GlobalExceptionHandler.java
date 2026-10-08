package com.github.cuixudong74.contentmodeljava.web;

import com.github.cuixudong74.contentmodeljava.exception.ContentModelException;
import com.github.cuixudong74.contentmodeljava.exception.ErrorCode;
import com.github.cuixudong74.contentmodeljava.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain errors into HTTP responses with stable error codes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ContentModelException.class)
    public ResponseEntity<ErrorResponse> handle(ContentModelException ex) {
        return ResponseEntity.status(statusFor(ex.getCode()))
                .body(new ErrorResponse(ex.getCode().name(), ex.getMessage(), ex.getDetails()));
    }

    private HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case TYPE_NOT_FOUND, ENTRY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ENTRY_NOT_PUBLISHED -> HttpStatus.CONFLICT;
            case VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case TYPE_ALREADY_EXISTS -> HttpStatus.CONFLICT;
            case VALIDATION_FAILED, FIELD_DUPLICATE, FIELD_INVALID_DEFINITION,
                    FIELD_EVOLUTION_UNSUPPORTED, REFERENCE_TARGET_NOT_FOUND,
                    REFERENCE_TARGET_NOT_PUBLISHED, REFERENCE_INTEGRITY_VIOLATION ->
                    HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
