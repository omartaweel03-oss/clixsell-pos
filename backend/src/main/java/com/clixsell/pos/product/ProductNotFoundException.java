package com.clixsell.pos.product;

/** No product has this id. Returned by the API as 404 Not Found. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(long productId) {
        super("Product " + productId + " was not found");
    }
}
