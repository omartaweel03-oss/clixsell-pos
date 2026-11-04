package com.clixsell.pos.common;

import java.util.Map;

/**
 * Submitted data breaks one or more rules. Returned by the API as
 * 400 Bad Request with one message per invalid field.
 */
public class ValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ValidationException(Map<String, String> fieldErrors) {
        super("Data is not valid: " + fieldErrors);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    /** Field name → error message, e.g. "username" → "Username is required". */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
