package com.clixsell.pos.common;

/**
 * The requested record does not exist <b>for the current user</b>. Returned as 404.
 *
 * <p>Records of another store are reported as "not found" too, so a user
 * cannot even learn that another store's record exists.
 */
public class NotFoundException extends RuntimeException {

    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
