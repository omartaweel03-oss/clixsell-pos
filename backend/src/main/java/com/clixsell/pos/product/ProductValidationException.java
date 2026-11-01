package com.clixsell.pos.product;

import java.util.Map;

/** A product breaks one or more business rules. Returned by the API as 400 Bad Request. */
public class ProductValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ProductValidationException(Map<String, String> fieldErrors) {
        super("Product data is not valid: " + fieldErrors);
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    /** Field name → error message, e.g. "barcode" → "Barcode must be 8 to 14 digits". */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
