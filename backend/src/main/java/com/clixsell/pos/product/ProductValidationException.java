package com.clixsell.pos.product;

import com.clixsell.pos.common.ValidationException;

import java.util.Map;

/** A product breaks one or more business rules. Returned by the API as 400 Bad Request. */
public class ProductValidationException extends ValidationException {

    public ProductValidationException(Map<String, String> fieldErrors) {
        super(fieldErrors);
    }
}
