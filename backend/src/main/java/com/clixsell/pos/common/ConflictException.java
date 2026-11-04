package com.clixsell.pos.common;

import java.util.Map;

/**
 * The data conflicts with an existing record (e.g. a username or store name
 * that is already taken). Returned as 409 Conflict.
 */
public class ConflictException extends RuntimeException {

    private final String code;
    private final String field;

    public ConflictException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return field == null ? Map.of() : Map.of(field, getMessage());
    }
}
