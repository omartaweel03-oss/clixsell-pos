package com.clixsell.pos.sale;

import java.math.BigDecimal;
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
        PaymentMethod paymentMethod,
        // Cash only, optional: what the customer handed over. Checked against the total the
        // server calculates; the change is returned, never recorded as revenue.
        BigDecimal cashReceived,
        // CHEQUE only, required then: the cheque handed over (its amount must be the invoice total)
        Cheque cheque
) {
    /** The cheque details (feature 9). amount is optional; when sent it must equal the server's total. */
    public record Cheque(String number, String bank, BigDecimal amount, java.time.LocalDate dueDate, String notes) {
    }

    public SaleRequest(Long saleId, List<Line> items, DiscountType discountType, Double discountValue,
                       PaymentMethod paymentMethod, BigDecimal cashReceived) {
        this(saleId, items, discountType, discountValue, paymentMethod, cashReceived, null);
    }

    /** The earlier form without the cash amount (suspend / existing callers). */
    public SaleRequest(Long saleId, List<Line> items, DiscountType discountType, Double discountValue,
                       PaymentMethod paymentMethod) {
        this(saleId, items, discountType, discountValue, paymentMethod, null, null);
    }

    /** packageId: optional carton / box of the product (feature 6); null = pieces. */
    public record Line(Long productId, Integer quantity, Long packageId) {
        public Line(Long productId, Integer quantity) {
            this(productId, quantity, null);
        }
    }
}
