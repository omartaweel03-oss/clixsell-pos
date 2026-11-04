package com.clixsell.pos.sale;

import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.LocalDateTimeTextConverter;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.product.Product;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.user.User;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An invoice / sale and its business logic (report: Sale class, SALES table,
 * Figures 3.12, 3.14 and 3.17).
 *
 * <ul>
 *   <li>{@link #addItem} – add a product (same product again = higher quantity)</li>
 *   <li>{@link #calculateTotal} – subtotal → discount (limited per store) → tax → total</li>
 *   <li>{@link #suspend} / {@link #cancel} – pause or cancel the invoice</li>
 *   <li>{@link #completeSale} – check stock, take it out of inventory, record the
 *       payment and issue the QR receipt</li>
 * </ul>
 * Every sale belongs to one store and is made by one cashier (store_id, cashier_id).
 */
@Entity
@Table(name = "sales")
public class Sale {

    public static final int MAX_LINES = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sale_id")
    private Long saleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cashier_id", nullable = false)
    private User cashier;

    @Column(name = "invoice_number", nullable = false, updatable = false)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SaleStatus status;

    @Column(nullable = false)
    private double subtotal;

    @Column(nullable = false)
    private double discount;

    @Column(name = "tax_rate", nullable = false)
    private double taxRate;

    @Column(nullable = false)
    private double tax;

    @Column(nullable = false)
    private double total;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("saleItemId")
    private List<SaleItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Payment> payments = new ArrayList<>();

    @OneToOne(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
    private Receipt receipt;

    protected Sale() {
    }

    /**
     * Starts a new invoice of {@code store} made by {@code cashier}. It takes the
     * store's next invoice number, so call it only when the invoice is saved.
     */
    public static Sale start(Store store, User cashier) {
        Sale sale = new Sale();
        sale.store = store;
        sale.cashier = cashier;
        sale.invoiceNumber = store.takeNextInvoiceNumber();
        sale.createdAt = now();
        sale.updatedAt = sale.createdAt;
        sale.taxRate = store.getTaxRate();
        return sale;
    }

    // ── Items ─────────────────────────────────────────────────────────────

    /** Removes every line (used before the lines are sent again). */
    public void clearItems() {
        requireOpen();
        items.clear();
    }

    /**
     * Adds {@code quantity} units of {@code product} at its current selling
     * price. A product already on the invoice gets a higher quantity instead
     * of a second line.
     */
    public void addItem(Product product, int quantity) {
        requireOpen();
        SaleItem.validateQuantity(quantity);
        if (!product.belongsTo(store.getStoreId())) {
            throw new IllegalArgumentException("Product of another store");
        }
        for (SaleItem item : items) {
            if (item.getProduct() != null && Objects.equals(item.getProduct().getProductId(), product.getProductId())) {
                item.updateQuantity(item.getQuantity() + quantity);
                return;
            }
        }
        if (items.size() >= MAX_LINES) {
            throw new ValidationException(Map.of("items", "An invoice can have at most " + MAX_LINES + " products"));
        }
        items.add(SaleItem.of(this, product, quantity));
    }

    // ── Totals ────────────────────────────────────────────────────────────

    /**
     * Calculates the invoice (SR10):
     * subtotal = Σ line totals; discount (amount or % of subtotal, at most the
     * store's maximum %); tax = (subtotal − discount) × store tax rate;
     * total = subtotal − discount + tax. All rounded to 2 decimals.
     *
     * @throws ValidationException for an invalid or too large discount
     */
    public void calculateTotal(DiscountType discountType, Double discountValue) {
        requireOpen();
        BigDecimal sub = BigDecimal.ZERO;
        for (SaleItem item : items) {
            sub = sub.add(Money.of(item.getLineTotal()));
        }
        subtotal = Money.round(sub);

        BigDecimal disc = discountAmount(Money.of(subtotal), discountType, discountValue);
        BigDecimal maxDisc = Money.of(subtotal).multiply(Money.of(store.getMaxDiscountPercent()))
                .divide(BigDecimal.valueOf(100));
        if (disc.compareTo(maxDisc.setScale(2, java.math.RoundingMode.HALF_UP)) > 0) {
            throw new ValidationException(Map.of("discountValue",
                    "Discount cannot be more than " + trim(store.getMaxDiscountPercent()) + "% of the subtotal"));
        }
        discount = Money.round(disc);

        taxRate = store.getTaxRate();
        BigDecimal taxable = Money.of(subtotal).subtract(Money.of(discount));
        tax = Money.round(taxable.multiply(Money.of(taxRate)).divide(BigDecimal.valueOf(100)));
        total = Money.round(taxable.add(Money.of(tax)));
        updatedAt = now();
    }

    private static BigDecimal discountAmount(BigDecimal subtotal, DiscountType type, Double value) {
        if (value == null || value == 0) return BigDecimal.ZERO;
        if (value.isNaN() || value.isInfinite() || value < 0) {
            throw new ValidationException(Map.of("discountValue", "Discount must be 0 or more"));
        }
        if (type == DiscountType.PERCENT) {
            if (value > 100) {
                throw new ValidationException(Map.of("discountValue", "Discount cannot be more than 100%"));
            }
            return subtotal.multiply(Money.of(value)).divide(BigDecimal.valueOf(100));
        }
        return Money.of(value);
    }

    // ── Invoice life ──────────────────────────────────────────────────────

    /** Pauses the invoice so it can be resumed later (SR9). Stock is not reserved. */
    public void suspend() {
        requireOpen();
        requireItems();
        status = SaleStatus.SUSPENDED;
        updatedAt = now();
    }

    /** Cancels a suspended invoice. It is kept, marked CANCELED, for the record. */
    public void cancel() {
        if (status != SaleStatus.SUSPENDED) {
            throw new ConflictException("SALE_NOT_SUSPENDED", null, "Only a suspended invoice can be canceled");
        }
        status = SaleStatus.CANCELED;
        updatedAt = now();
    }

    /**
     * Completes the sale (Figure 3.17, steps 9–15): every product must have
     * enough stock; then the stock is reduced, the payment recorded and the
     * QR receipt issued. If any product is short, nothing changes.
     *
     * @throws InsufficientStockException listing the products that are short
     */
    public void completeSale(PaymentMethod method) {
        requireOpen();
        requireItems();
        if (method == null) {
            throw new ValidationException(Map.of("paymentMethod", "Choose a payment method"));
        }
        Map<String, String> shortages = new LinkedHashMap<>();
        for (SaleItem item : items) {
            Product p = item.getProduct();
            if (p == null) {
                shortages.put(item.getProductName(), "This product is no longer available");
            } else if (p.getQuantity() < item.getQuantity()) {
                shortages.put(item.getProductName(), "Only " + p.getQuantity() + " left in stock");
            }
        }
        if (!shortages.isEmpty()) {
            throw new InsufficientStockException(shortages);
        }
        for (SaleItem item : items) {
            item.getProduct().updateStock(-item.getQuantity());
        }
        LocalDateTime now = now();
        status = SaleStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
        payments.add(new Payment(this, method, total, now));
        receipt = Receipt.issue(this, now);
    }

    private void requireOpen() {
        if (status == SaleStatus.COMPLETED || status == SaleStatus.CANCELED) {
            throw new ConflictException("SALE_CLOSED", null, "This invoice is already " + status.name().toLowerCase());
        }
    }

    private void requireItems() {
        if (items.isEmpty()) {
            throw new ValidationException(Map.of("items", "Add at least one product to the invoice"));
        }
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public boolean belongsTo(long storeId) {
        return store.getStoreId() != null && store.getStoreId() == storeId;
    }

    /** The payment method of a completed sale, or null. */
    public PaymentMethod getPaymentMethod() {
        return payments.isEmpty() ? null : payments.get(0).getMethod();
    }

    public Long getSaleId() { return saleId; }
    public Store getStore() { return store; }
    public User getCashier() { return cashier; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public SaleStatus getStatus() { return status; }
    public double getSubtotal() { return subtotal; }
    public double getDiscount() { return discount; }
    public double getTaxRate() { return taxRate; }
    public double getTax() { return tax; }
    public double getTotal() { return total; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public List<SaleItem> getItems() { return Collections.unmodifiableList(items); }
    public Receipt getReceipt() { return receipt; }
}
