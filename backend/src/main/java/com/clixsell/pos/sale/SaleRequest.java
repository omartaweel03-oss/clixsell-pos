package com.clixsell.pos.sale;

import java.util.List;

/**
 * The current invoice sent by the POS screen to suspend or complete it.
 * Only product ids and quantities are sent: prices always come from the
 * database, so the screen cannot change them.
 *
 * @param saleId        id of a resumed suspended invoice, or null for a new one
 * @param paymentMethod required to complete a sale, ignored when suspending
 */
public record SaleRequest(
        Long saleId,
        List<Line> items,
        DiscountType discountType,
        Double discountValue,
        PaymentMethod paymentMethod
) {
    public record Line(Long productId, Integer quantity) {
    }
}
