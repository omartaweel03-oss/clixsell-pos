package com.clixsell.pos.returns;

/** A return entered by the cashier: which line of which completed sale, how many, and why. */
public record ReturnRequest(Long saleId, Long saleItemId, Integer quantity, String reason, String requestKey) {
    /** Without a request key (older clients): no duplicate protection beyond the returnable quantity. */
    public ReturnRequest(Long saleId, Long saleItemId, Integer quantity, String reason) {
        this(saleId, saleItemId, quantity, reason, null);
    }
}
