package com.clixsell.pos.sale;

/**
 * Loyalty readiness (requirement 8): published after a sale is completed, inside the sale's
 * transaction. NOTHING listens to it today. A future loyalty module would subscribe with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so points are only given for sales
 * that were really saved, and would key them on {@code saleId} (one award per sale). Amounts are in
 * agorot; {@code paidMinor} is what the customer actually paid (total + cash rounding).
 * No customer is identified at the POS yet: that needs its own approved schema (see the loyalty
 * readiness document).
 */
public record SaleCompletedEvent(long storeId, long saleId, long cashierId, String paymentMethod, long totalMinor,
                                 long paidMinor) {
}
