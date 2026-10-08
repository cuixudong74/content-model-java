package com.github.cuixudong74.contentmodeljava.error;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ContentModelException.class)
    public ResponseEntity<ErrorResponse> handle(ContentModelException ex) {
        return ResponseEntity.status(ex.code().httpStatus())
                .body(new ErrorResponse(ex.code().name(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(new ErrorResponse(ErrorCode.BAD_REQUEST.name(), ex.getMessage(), java.util.List.of()));
    }
}
