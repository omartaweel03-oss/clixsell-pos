package com.clixsell.pos.product;

/** Another product already uses this barcode. Returned by the API as 409 Conflict. */
public class DuplicateBarcodeException extends RuntimeException {

    public DuplicateBarcodeException(String barcode) {
        super("Another product in this store already uses barcode " + barcode);
    }
}
