package com.clixsell.pos.sale;

/** Life of an invoice: SUSPENDED (paused) → COMPLETED (paid) or CANCELED. */
public enum SaleStatus {
    SUSPENDED,
    COMPLETED,
    CANCELED
}
