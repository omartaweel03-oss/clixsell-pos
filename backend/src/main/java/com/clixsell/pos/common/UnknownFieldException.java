package com.clixsell.pos.common;

/** The request contains a field this endpoint does not accept. Returned as 400 UNKNOWN_FIELD. */
public class UnknownFieldException extends RuntimeException {

    private final String field;

    public UnknownFieldException(String field) {
        super("Field '" + field + "' is not accepted here");
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
