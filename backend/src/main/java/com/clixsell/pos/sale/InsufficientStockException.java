package com.clixsell.pos.sale;

import java.util.Map;

/**
 * Some products of the invoice do not have enough stock. Returned as
 * 409 Conflict, listing each product and how many are left.
 * Nothing is saved: the sale and all stock changes are rolled back.
 */
public class InsufficientStockException extends RuntimeException {

    private final Map<String, String> shortages;

    public InsufficientStockException(Map<String, String> shortages) {
        super("Not enough stock for: " + String.join(", ", shortages.keySet()));
        this.shortages = Map.copyOf(shortages);
    }

    /** Product name → "Only N left in stock". */
    public Map<String, String> getShortages() {
        return shortages;
    }
}
