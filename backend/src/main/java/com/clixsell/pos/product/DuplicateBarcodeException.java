package com.clixsell.pos.product;

/** Another product already uses this barcode. Returned by the API as 409 Conflict. */
public class DuplicateBarcodeException extends RuntimeException {

    public DuplicateBarcodeException(String barcode) {
        super("Another product already uses barcode " + barcode);
    }
}
