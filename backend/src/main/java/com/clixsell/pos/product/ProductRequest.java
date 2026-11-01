package com.clixsell.pos.product;

/**
 * Data sent by the Product Screen to insert or update a product (JSON body).
 *
 * <p>Numbers use wrapper types so a missing value arrives as {@code null} and
 * is reported by {@link Product#validate(ProductRequest)} instead of silently
 * becoming 0. The image arrives as a base64 string in JSON and is decoded to
 * bytes automatically.
 */
public record ProductRequest(
        String name,
        String barcode,
        String category,
        Double sellingPrice,
        Integer quantity,
        Integer minStock,
        String supplierName,
        byte[] image,
        String imageName
) {
}
