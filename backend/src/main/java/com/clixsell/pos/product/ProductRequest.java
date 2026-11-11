package com.clixsell.pos.product;

/**
 * Data sent by the Product Screen to insert or update a product (JSON body).
 *
 * <p>Numbers use wrapper types so a missing value arrives as {@code null} and
 * is reported by {@link Product#validate(ProductRequest)} instead of silently
 * becoming 0. The image arrives as a base64 string in JSON and is decoded to
 * bytes automatically. {@code costPrice} (purchase cost) is optional: null = unknown.
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
        String imageName,
        Double costPrice
) {
    /** A request without a purchase cost (cost unknown). */
    public ProductRequest(String name, String barcode, String category, Double sellingPrice, Integer quantity,
                          Integer minStock, String supplierName, byte[] image, String imageName) {
        this(name, barcode, category, sellingPrice, quantity, minStock, supplierName, image, imageName, null);
    }
}
