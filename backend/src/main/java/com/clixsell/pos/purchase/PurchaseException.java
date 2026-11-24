package com.clixsell.pos.purchase;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * A purchasing request that cannot be done, with a stable code and, where useful,
 * structured details (for example which open order holds a product, or which recent
 * orders need an acknowledgment).
 */
public class PurchaseException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fieldErrors;
    private final Object details;

    public PurchaseException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of(), null);
    }

    public PurchaseException(HttpStatus status, String code, String message, Map<String, String> fieldErrors,
                             Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors == null ? Map.of() : fieldErrors;
        this.details = details;
    }

    static PurchaseException notFound(String code, String message) {
        return new PurchaseException(HttpStatus.NOT_FOUND, code, message);
    }

    static PurchaseException conflict(String code, String message) {
        return new PurchaseException(HttpStatus.CONFLICT, code, message);
    }

    static PurchaseException badRequest(String code, String field, String message) {
        return new PurchaseException(HttpStatus.BAD_REQUEST, code, message, Map.of(field, message), null);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Map<String, String> getFieldErrors() { return fieldErrors; }
    public Object getDetails() { return details; }
}
