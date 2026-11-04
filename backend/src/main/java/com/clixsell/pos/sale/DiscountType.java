package com.clixsell.pos.sale;

/** How the cashier entered the invoice discount. */
public enum DiscountType {
    /** A money amount, e.g. 2.50 */
    AMOUNT,
    /** A percentage of the subtotal, e.g. 5 (%) */
    PERCENT
}
