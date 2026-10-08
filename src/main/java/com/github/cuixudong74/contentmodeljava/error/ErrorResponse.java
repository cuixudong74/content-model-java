package com.github.cuixudong74.contentmodeljava.error;

import java.util.List;

/**
 * JSON body for all error responses.
 *
 * @param code    stable error code name, e.g. DRAFT_VERSION_CONFLICT
 * @param message human-readable summary
 * @param details field-level / conflict explanations, possibly empty
 */
public record ErrorResponse(String code, String message, List<String> details) {
}
