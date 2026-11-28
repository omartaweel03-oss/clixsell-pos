package com.clixsell.pos.product;

import com.clixsell.pos.activity.ActivityEventType;
import com.clixsell.pos.activity.ActivityLogService;
import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRepository;
import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.supplier.Supplier;
import com.clixsell.pos.supplier.SupplierService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Coordinates product operations between the API and the database.
 *
 * <p>The rules for a valid product live in {@link Product}; this service adds
 * the rules that need the database (barcode unique within the store, product
 * must exist in the store) and runs each operation in a transaction.
 *
 * <p>Every method takes the {@code storeId} of the logged-in user and only
 * ever reads or changes that store's products. A product of another store is
 * treated as not found.
 *
 * <p>Changes that Guardian Mode watches (price, cost, stock by hand, delete)
 * are written to the activity log with the logged-in user, in the same
 * transaction. Nothing else about these operations changes.
 */
@Service
@Transactional
public class ProductService {

    private final ProductRepository products;
    private final SupplierService suppliers;
    private final StoreRepository stores;
    private final ActivityLogService activity;
    private final Random random = new SecureRandom();

    public ProductService(ProductRepository products, SupplierService suppliers, StoreRepository stores,
                          ActivityLogService activity) {
        this.products = products;
        this.suppliers = suppliers;
        this.stores = stores;
        this.activity = activity;
    }

    @Transactional(readOnly = true)
    public List<Product> listProducts(long storeId) {
        return products.findAllByStore_StoreIdOrderByProductIdDesc(storeId);
    }

    @Transactional(readOnly = true)
    public Product getProduct(long storeId, long productId) {
        return products.findByProductIdAndStore_StoreId(productId, storeId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /** INSERT: validates, checks the barcode is unique in the store, then saves a new row. */
    public Product createProduct(long storeId, ProductRequest request) {
        Product.validate(request);
        String barcode = request.barcode().trim();
        if (products.existsByStore_StoreIdAndBarcode(storeId, barcode) || products.countPackageBarcode(storeId, barcode) > 0) {
            throw new DuplicateBarcodeException(barcode);
        }
        Store store = stores.findById(storeId).orElseThrow();
        Supplier supplier = suppliers.findOrCreate(store, request.supplierName());
        rejectInactive(supplier);
        return products.save(Product.create(request, supplier, store));
    }

    /**
     * UPDATE: finds the product in the logged-in user's store, validates, checks
     * the barcode, then saves. A changed price, cost or quantity is logged with
     * the old and new value (an unchanged value is not logged).
     */
    public Product updateProduct(CurrentUser user, long productId, ProductRequest request) {
        long storeId = user.requireStoreId();
        Product product = getProduct(storeId, productId);
        Product.validate(request);
        String barcode = request.barcode().trim();
        if (products.existsByStore_StoreIdAndBarcodeAndProductIdNot(storeId, barcode, productId)
                || products.countPackageBarcode(storeId, barcode) > 0) {
            throw new DuplicateBarcodeException(barcode);
        }
        double oldPrice = product.getSellingPrice();
        Double oldCost = product.getCostPrice();
        int oldQuantity = product.getQuantity();
        Supplier supplier = suppliers.findOrCreate(product.getStore(), request.supplierName());
        // D16: a product already linked to an inactive supplier may still be edited; it may not be
        // newly linked to one. Nothing here ever reactivates a supplier.
        if (!supplier.getSupplierId().equals(product.getSupplier().getSupplierId())) rejectInactive(supplier);
        product.updateDetails(request, supplier);
        Product saved = products.save(product);

        if (Double.compare(oldPrice, saved.getSellingPrice()) != 0) {
            logChange(user, saved, ActivityEventType.PRICE_CHANGED, oldPrice, saved.getSellingPrice());
        }
        if (!Objects.equals(oldCost, saved.getCostPrice())) {
            logChange(user, saved, ActivityEventType.COST_CHANGED, oldCost, saved.getCostPrice());
        }
        if (oldQuantity != saved.getQuantity()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("old", oldQuantity);
            d.put("new", saved.getQuantity());
            d.put("change", saved.getQuantity() - oldQuantity);
            activity.record(user, ActivityEventType.STOCK_ADJUSTED, "PRODUCT", saved.getProductId(), saved.getName(), d);
        }
        return saved;
    }

    /**
     * DELETE: removes the product's row (only if it belongs to the logged-in
     * user's store). The log keeps a snapshot, because the row is gone.
     */
    public void deleteProduct(CurrentUser user, long productId) {
        Product product = getProduct(user.requireStoreId(), productId);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("barcode", product.getBarcode());
        d.put("category", product.getCategory());
        d.put("sellingPrice", product.getSellingPrice());
        d.put("costPrice", product.getCostPrice());
        d.put("quantity", product.getQuantity());
        activity.record(user, ActivityEventType.PRODUCT_DELETED, "PRODUCT", product.getProductId(), product.getName(), d);
        products.delete(product);
    }

    /** D16: a new link to a deactivated supplier is refused (activate it in Suppliers first). */
    private static void rejectInactive(Supplier supplier) {
        if (!supplier.isActive()) {
            throw new ConflictException("SUPPLIER_INACTIVE", "supplierName", "Supplier '" + supplier.getName()
                    + "' is inactive. Activate it in Suppliers or choose another supplier.");
        }
    }

    private void logChange(CurrentUser user, Product p, ActivityEventType type, Double oldValue, Double newValue) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("old", oldValue);
        d.put("new", newValue);
        activity.record(user, type, "PRODUCT", p.getProductId(), p.getName(), d);
    }

    /** Generates a barcode that no product of the store uses yet. */
    @Transactional(readOnly = true)
    public String generateUniqueBarcode(long storeId) {
        String code;
        do {
            code = Product.generateBarcode(random);
        } while (products.existsByStore_StoreIdAndBarcode(storeId, code));
        return code;
    }
}
