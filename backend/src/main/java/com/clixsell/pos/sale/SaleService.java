package com.clixsell.pos.sale;

import com.clixsell.pos.activity.ActivityEventType;
import com.clixsell.pos.activity.ActivityLogService;
import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.product.Product;
import com.clixsell.pos.product.ProductRepository;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRepository;
import com.clixsell.pos.user.User;
import com.clixsell.pos.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * POS operations for the logged-in cashier's store (Figure 3.17).
 *
 * <p>Each operation is one database transaction: completing a sale checks the
 * stock, reduces it and saves the sale, its lines, payment and receipt
 * together, or saves nothing at all. The store always comes from the logged-in
 * user, so one store can never see or change another store's invoices.
 *
 * <p>For Guardian Mode the activity log records, with the LOGGED-IN user (not
 * the cashier who started the invoice): a completed sale that has a discount
 * (the user who completed it set the final discount) and a canceled invoice.
 */
@Service
@Transactional
public class SaleService {

    private final SaleRepository sales;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final UserRepository users;
    private final ActivityLogService activity;

    public SaleService(SaleRepository sales, ProductRepository products, StoreRepository stores,
                       UserRepository users, ActivityLogService activity) {
        this.sales = sales;
        this.products = products;
        this.stores = stores;
        this.users = users;
        this.activity = activity;
    }

    /** Completes the current invoice (new or resumed): stock, payment and receipt. */
    public SaleResponse complete(CurrentUser user, SaleRequest request) {
        Sale sale = openSale(user, request);
        BigDecimal change = cashChange(request, sale);
        if (request.saleId() != null) {
            // A resumed invoice already exists: Hibernate writes INSERTs (the payment) before UPDATEs
            // (the recalculated total), so the payment would briefly sit next to the OLD total. Write
            // the new total first; V7 checks every payment against the invoice it pays.
            sales.saveAndFlush(sale);
        }
        sale.completeSale(request.paymentMethod());
        Sale saved = sales.save(sale);
        if (saved.getDiscount() > 0) {
            // The discount is calculated again from THIS request (openSale → calculateTotal),
            // so the user completing the sale is the one who applied the final discount.
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("invoiceNumber", saved.getInvoiceNumber());
            d.put("subtotal", saved.getSubtotal());
            d.put("discount", saved.getDiscount());
            d.put("percent", BigDecimal.valueOf(saved.getDiscount() * 100 / saved.getSubtotal())
                    .setScale(2, RoundingMode.HALF_UP).doubleValue());
            d.put("maxPercentAtSale", saved.getStore().getMaxDiscountPercent());
            d.put("total", saved.getTotal());
            d.put("startedByUserId", saved.getCashier().getUserId());
            d.put("resumed", request.saleId() != null);
            activity.record(user, ActivityEventType.DISCOUNT_APPLIED, "SALE", saved.getSaleId(),
                    saved.getInvoiceNumber(), d);
        }
        SaleResponse response = SaleResponse.from(saved);
        return change == null ? response
                : response.withCash(request.cashReceived().doubleValue(), change.doubleValue());
    }

    /**
     * Cash amount received (optional, CASH only): must cover the total the server calculated, with at
     * most 2 decimals. Returns the change to give back, or null when no amount was sent. The payment
     * itself is still recorded at the invoice total, so change is never revenue.
     */
    static BigDecimal cashChange(SaleRequest request, Sale sale) {
        BigDecimal received = request.cashReceived();
        if (received == null) return null;
        if (request.paymentMethod() != PaymentMethod.CASH) {
            throw new ValidationException(Map.of("cashReceived", "The amount received is only for cash payments"));
        }
        if (received.signum() <= 0 || received.stripTrailingZeros().scale() > 2) {
            throw new ValidationException(Map.of("cashReceived", "Enter the amount received, with at most 2 decimals"));
        }
        BigDecimal total = BigDecimal.valueOf(sale.getTotal()).setScale(2, RoundingMode.HALF_UP);
        if (received.compareTo(total) < 0) {
            throw new ValidationException(Map.of("cashReceived", "The amount received is "
                    + received.setScale(2, RoundingMode.HALF_UP).toPlainString() + "; still to pay: "
                    + total.subtract(received).setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
        return received.subtract(total).setScale(2, RoundingMode.HALF_UP);
    }

    /** Suspends the current invoice (SR9). A resumed invoice keeps its number. */
    public SaleResponse suspend(CurrentUser user, SaleRequest request) {
        Sale sale = openSale(user, request);
        sale.suspend();
        return SaleResponse.from(sales.save(sale));
    }

    @Transactional(readOnly = true)
    public List<SaleResponse> listSuspended(long storeId) {
        return sales.findAllByStore_StoreIdAndStatusOrderBySaleIdDesc(storeId, SaleStatus.SUSPENDED).stream()
                .map(SaleResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public SaleResponse get(long storeId, long saleId) {
        return SaleResponse.from(find(storeId, saleId));
    }

    /**
     * Cancels a suspended invoice of the logged-in user's store; it stays in the
     * database marked CANCELED. The log records the user who canceled it.
     */
    public SaleResponse cancelSuspended(CurrentUser user, long saleId) {
        Sale sale = find(user.requireStoreId(), saleId);
        sale.cancel();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("invoiceNumber", sale.getInvoiceNumber());
        d.put("total", sale.getTotal());
        d.put("startedByUserId", sale.getCashier().getUserId());
        activity.record(user, ActivityEventType.INVOICE_CANCELED, "SALE", sale.getSaleId(), sale.getInvoiceNumber(), d);
        return SaleResponse.from(sale);
    }

    /** A completed sale of the store by invoice number or receipt QR code (for returns). */
    @Transactional(readOnly = true)
    public SaleResponse lookup(long storeId, String code) {
        String c = code == null ? "" : code.trim();
        return sales.findCompletedByCode(storeId, c).map(SaleResponse::from)
                .orElseThrow(() -> new NotFoundException("SALE_NOT_FOUND",
                        "No completed invoice or receipt '" + c + "' in this store"));
    }

    Sale find(long storeId, long saleId) {
        return sales.findBySaleIdAndStore_StoreId(saleId, storeId)
                .orElseThrow(() -> new NotFoundException("SALE_NOT_FOUND", "Invoice " + saleId + " was not found"));
    }

    /** The invoice to work on: a resumed suspended one, or a new one; filled from the request. */
    private Sale openSale(CurrentUser user, SaleRequest request) {
        long storeId = user.requireStoreId();
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new ValidationException(Map.of("items", "Add at least one product to the invoice"));
        }
        Sale sale;
        if (request.saleId() != null) {
            sale = find(storeId, request.saleId());
            if (sale.getStatus() != SaleStatus.SUSPENDED) {
                throw new ConflictException("SALE_NOT_SUSPENDED", null,
                        "Invoice " + sale.getInvoiceNumber() + " is no longer suspended");
            }
            sale.clearItems();
        } else {
            Store store = stores.findById(storeId).orElseThrow();
            User cashier = users.findById(user.userId()).orElseThrow();
            sale = Sale.start(store, cashier);
        }
        for (SaleRequest.Line line : request.items()) {
            if (line == null || line.productId() == null) {
                throw new ValidationException(Map.of("items", "Every line needs a product"));
            }
            Product product = products.findByProductIdAndStore_StoreId(line.productId(), storeId)
                    .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND",
                            "Product " + line.productId() + " is not available in this store"));
            sale.addItem(product, line.quantity() == null ? 0 : line.quantity());
        }
        sale.calculateTotal(request.discountType(), request.discountValue());
        return sale;
    }
}
