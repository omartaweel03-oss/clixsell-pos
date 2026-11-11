package com.clixsell.pos.product;

import java.time.LocalDateTime;

/**
 * Product data returned by the API (JSON). Includes the results of the
 * business logic: {@code stockStatus} and {@code stockValue}. {@code costPrice}
 * is only filled in for Store Admins.
 */
public record ProductResponse(
        Long productId,
        Long storeId,
        String name,
        String barcode,
        String category,
        double sellingPrice,
        int quantity,
        int minStock,
        Long supplierId,
        String supplierName,
        StockStatus stockStatus,
        double stockValue,
        byte[] image,
        String imageName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Double costPrice
) {
    /** Without the purchase cost (e.g. for cashiers). */
    public static ProductResponse from(Product p) {
        return from(p, false);
    }

    /** {@code withCost} only for Store Admins: cashiers must not see purchase costs. */
    public static ProductResponse from(Product p, boolean withCost) {
        return new ProductResponse(
                p.getProductId(),
                p.getStore().getStoreId(),
                p.getName(),
                p.getBarcode(),
                p.getCategory(),
                p.getSellingPrice(),
                p.getQuantity(),
                p.getMinStock(),
                p.getSupplier().getSupplierId(),
                p.getSupplier().getName(),
                p.getStockStatus(),
                p.getStockValue(),
                p.getImage(),
                p.getImageName(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                withCost ? p.getCostPrice() : null);
    }
}
