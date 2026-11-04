package com.clixsell.pos.returns;

/** A return entered by the cashier: which line of which completed sale, how many, and why. */
public record ReturnRequest(Long saleId, Long saleItemId, Integer quantity, String reason) {
}
