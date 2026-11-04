package com.clixsell.pos.sale;

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

import java.util.List;
import java.util.Map;

/**
 * POS operations for the logged-in cashier's store (Figure 3.17).
 *
 * <p>Each operation is one database transaction: completing a sale checks the
 * stock, reduces it and saves the sale, its lines, payment and receipt
 * together, or saves nothing at all. The store always comes from the logged-in
 * user, so one store can never see or change another store's invoices.
 */
@Service
@Transactional
public class SaleService {

    private final SaleRepository sales;
    private final ProductRepository products;
    private final StoreRepository stores;
    private final UserRepository users;

    public SaleService(SaleRepository sales, ProductRepository products, StoreRepository stores,
                       UserRepository users) {
        this.sales = sales;
        this.products = products;
        this.stores = stores;
        this.users = users;
    }

    /** Completes the current invoice (new or resumed): stock, payment and receipt. */
    public SaleResponse complete(CurrentUser user, SaleRequest request) {
        Sale sale = openSale(user, request);
        sale.completeSale(request.paymentMethod());
        return SaleResponse.from(sales.save(sale));
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

    /** Cancels a suspended invoice; it stays in the database marked CANCELED. */
    public SaleResponse cancelSuspended(long storeId, long saleId) {
        Sale sale = find(storeId, saleId);
        sale.cancel();
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
