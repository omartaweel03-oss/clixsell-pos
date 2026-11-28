package com.clixsell.pos.returns;

import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.LocalDateTimeTextConverter;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.product.Product;
import com.clixsell.pos.sale.PaymentMethod;
import com.clixsell.pos.sale.Sale;
import com.clixsell.pos.sale.SaleItem;
import com.clixsell.pos.sale.SaleStatus;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A product returned by a customer through the cashier (report: ReturnProduct
 * class, RETURNS table, SR13, Figure 3.15).
 *
 * <ul>
 *   <li>{@link #validateReturn} – the sale is completed and recent enough, the
 *       quantity is not more than what was bought and not yet returned, a
 *       reason is given</li>
 *   <li>{@link #calculateRefund} – the part of what the customer actually paid
 *       (after discount, with tax) for these units</li>
 *   <li>{@link #restoreStock} – puts the units back into inventory</li>
 * </ul>
 * The refund uses the sale's own payment method.
 */
@Entity
@Table(name = "returns")
public class ReturnProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "return_id")
    private Long returnId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_item_id", nullable = false)
    private SaleItem saleItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    private User cashier;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private String reason;

    @Column(name = "refund_amount", nullable = false)
    private double refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false)
    private PaymentMethod refundMethod;

    /** V7: the open cash shift of the user who made the return (null = none). */
    @Column(name = "shift_id")
    private Long shiftId;

    /** V7: the refund paid back in agorot (= refund_amount × 100); null on returns made before V7. */
    @Column(name = "refund_minor")
    private Long refundMinor;

    /** V7, D4: the cash rounding INCLUDED in this refund (refund − exact share), agorot; 0 otherwise. */
    @Column(name = "rounding_minor", nullable = false)
    private long roundingMinor = 0;

    @Column(name = "return_date", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime returnDate;

    protected ReturnProduct() {
    }

    /**
     * Validates and records a return, puts the stock back and calculates the
     * refund. {@code maxDays} is the return period (14 days by default).
     */
    public static ReturnProduct process(Sale sale, SaleItem item, Integer quantity, String reason,
                                        User cashier, LocalDateTime now, int maxDays) {
        return process(sale, item, quantity, reason, cashier, now, maxDays, BigDecimal.ZERO, BigDecimal.ZERO, null, 0, false);
    }

    public static ReturnProduct process(Sale sale, SaleItem item, Integer quantity, String reason,
                                        User cashier, LocalDateTime now, int maxDays,
                                        BigDecimal refundedForLine, BigDecimal refundedForSale, Long shiftId) {
        return process(sale, item, quantity, reason, cashier, now, maxDays, refundedForLine, refundedForSale, shiftId, 0, false);
    }

    /**
     * As {@link #process(Sale, SaleItem, Integer, String, User, LocalDateTime, int)}, knowing what was
     * already refunded for this line and for the whole invoice (so the refunds never add up to more
     * than was paid), in the given cash shift.
     */
    /**
     * @param refundedForLine exact shares already refunded for this line
     * @param refundedForSale cash/amount already paid back for the whole invoice
     * @param cashStep        the store's cash rounding step (D4) for a CASH refund; 0 = none
     * @param lastReturn      this return leaves nothing returnable on the invoice
     */
    public static ReturnProduct process(Sale sale, SaleItem item, Integer quantity, String reason,
                                        User cashier, LocalDateTime now, int maxDays,
                                        BigDecimal refundedForLine, BigDecimal refundedForSale, Long shiftId,
                                        int cashStep, boolean lastReturn) {
        validateReturn(sale, item, quantity, reason, now, maxDays);
        ReturnProduct r = new ReturnProduct();
        r.store = sale.getStore();
        r.sale = sale;
        r.saleItem = item;
        r.product = item.getProduct();
        r.cashier = cashier;
        r.quantity = quantity;
        r.reason = reason.trim();
        long exact = calculateRefund(sale, item, quantity, refundedForLine).movePointRight(2).longValueExact();
        long left = sale.payableMinor() - refundedForSale.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        boolean cash = sale.getPaymentMethod() == PaymentMethod.CASH;
        long refund;
        if (cash && (cashStep > 0 || sale.getRoundingMinor() != 0)) {
            // rounded once; the last return pays exactly what is left of what the customer paid
            refund = lastReturn ? left : Math.min(exact + Sale.cashRounding(exact, cashStep), left);
            refund = Math.max(exact - 50, Math.min(exact + 50, refund)); // the database allows ±0.50
        } else {
            refund = Math.min(exact, left);
        }
        refund = Math.max(0, refund);
        r.roundingMinor = cash ? refund - exact : 0;
        if (!cash) refund = Math.min(refund, exact);
        r.refundAmount = BigDecimal.valueOf(refund, 2).doubleValue();
        r.refundMinor = refund;
        r.shiftId = shiftId;
        r.refundMethod = sale.getPaymentMethod();
        r.returnDate = now.withNano(0);
        r.restoreStock();
        return r;
    }

    static void validateReturn(Sale sale, SaleItem item, Integer quantity, String reason,
                               LocalDateTime now, int maxDays) {
        if (sale.getStatus() != SaleStatus.COMPLETED) {
            throw new ConflictException("SALE_NOT_COMPLETED", null, "Only a completed sale can be returned");
        }
        if (sale.getCompletedAt().plusDays(maxDays).isBefore(now)) {
            throw new ConflictException("RETURN_PERIOD_OVER", null,
                    "Returns are accepted within " + maxDays + " days of the sale");
        }
        if (item.getProduct() == null) {
            throw new ConflictException("PRODUCT_DELETED", null,
                    "This product was deleted, so it cannot be put back into stock");
        }
        Map<String, String> errors = new LinkedHashMap<>();
        if (quantity == null || quantity < 1) {
            errors.put("quantity", "Quantity must be at least 1");
        } else if (quantity > item.getReturnableQuantity()) {
            errors.put("quantity", "Only " + item.getReturnableQuantity() + " of this product can still be returned");
        }
        String r = reason == null ? "" : reason.trim();
        if (r.length() < 2) errors.put("reason", "Enter the reason for the return");
        else if (r.length() > 200) errors.put("reason", "Reason must be 200 characters or fewer");
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    /**
     * Refund = what the customer paid for these units: the line's share of the
     * invoice total (so discount and tax are included), per unit, × quantity.
     */
    static double calculateRefund(Sale sale, SaleItem item, int quantity) {
        return calculateRefund(sale, item, quantity, BigDecimal.ZERO).doubleValue();
    }

    /**
     * The exact share of this return, rounded to agorot, such that partial returns of a line never
     * add up to more than the line's share: the return that completes a line refunds exactly what is
     * left of it (so 3 × 1 unit of a 10.01 line gives 3.34 + 3.34 + 3.33).
     */
    static BigDecimal calculateRefund(Sale sale, SaleItem item, int quantity, BigDecimal refundedForLine) {
        if (sale.getSubtotal() <= 0) return BigDecimal.ZERO.setScale(2);
        BigDecimal paidForLine = BigDecimal.valueOf(sale.getTotal())
                .multiply(BigDecimal.valueOf(item.getLineTotal()))
                .divide(BigDecimal.valueOf(sale.getSubtotal()), 10, RoundingMode.HALF_UP);
        BigDecimal refund;
        if (quantity == item.getReturnableQuantity()) {
            refund = paidForLine.setScale(2, RoundingMode.HALF_UP).subtract(refundedForLine);
        } else {
            refund = paidForLine.multiply(BigDecimal.valueOf(quantity))
                    .divide(BigDecimal.valueOf(item.getQuantity()), 2, RoundingMode.HALF_UP);
        }
        return refund.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** Puts the returned units back into stock and counts them on the sale line. */
    void restoreStock() {
        // a returned carton puts all its pieces back (whole packages only)
        product.updateStock(quantity * saleItem.getUnitsPerPackage());
        saleItem.recordReturn(quantity);
    }

    public Long getReturnId() { return returnId; }
    public Sale getSale() { return sale; }
    public SaleItem getSaleItem() { return saleItem; }
    public int getQuantity() { return quantity; }
    public String getReason() { return reason; }
    public double getRefundAmount() { return refundAmount; }
    public PaymentMethod getRefundMethod() { return refundMethod; }
    public LocalDateTime getReturnDate() { return returnDate; }
    public Long getShiftId() { return shiftId; }
    public Long getRefundMinor() { return refundMinor; }
    public long getRoundingMinor() { return roundingMinor; }
}
