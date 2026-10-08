package com.github.cuixudong74.contentmodeljava.web.dto;

/**
 * Stable error envelope returned for every failed request.
 */
public record ErrorResponse(String code, String message, Object details) {
}
