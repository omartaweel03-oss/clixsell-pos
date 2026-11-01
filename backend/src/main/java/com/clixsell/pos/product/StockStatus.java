package com.clixsell.pos.product;

/** Stock level of a product, decided by {@link Product#getStockStatus()}. */
public enum StockStatus {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK
}
