package com.clixsell.pos.activity;

/**
 * What the activity log records (Guardian Mode Phase 1). Each event is written
 * by the backend, inside the same transaction as the action, with the
 * logged-in user who performed it.
 */
public enum ActivityEventType {
    /** A sale was completed with a discount. The user is the one who completed it. */
    DISCOUNT_APPLIED,
    /** A suspended invoice was canceled. The user is the one who canceled it. */
    INVOICE_CANCELED,
    /** A product was deleted (Store Admin). A snapshot of the product is kept. */
    PRODUCT_DELETED,
    /** The selling price was changed by hand. */
    PRICE_CHANGED,
    /** The purchase cost was changed by hand. */
    COST_CHANGED,
    /** The quantity in stock was changed by hand (not by a sale or a return). */
    STOCK_ADJUSTED,
    GUARDIAN_ENABLED,
    GUARDIAN_DISABLED
}
