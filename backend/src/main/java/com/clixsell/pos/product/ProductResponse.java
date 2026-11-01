package com.clixsell.pos.product;

import java.time.LocalDateTime;

/**
 * Product data returned by the API (JSON). Includes the results of the
 * business logic: {@code stockStatus} and {@code stockValue}.
 */
public record ProductResponse(
        Long productId,
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
        LocalDateTime updatedAt
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getProductId(),
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
                p.getUpdatedAt());
    }
}
