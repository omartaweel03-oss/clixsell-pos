package com.clixsell.pos.product;

import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRepository;
import com.clixsell.pos.supplier.Supplier;
import com.clixsell.pos.supplier.SupplierService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
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
 */
@Service
@Transactional
public class ProductService {

    private final ProductRepository products;
    private final SupplierService suppliers;
    private final StoreRepository stores;
    private final Random random = new SecureRandom();

    public ProductService(ProductRepository products, SupplierService suppliers, StoreRepository stores) {
        this.products = products;
        this.suppliers = suppliers;
        this.stores = stores;
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
        if (products.existsByStore_StoreIdAndBarcode(storeId, barcode)) {
            throw new DuplicateBarcodeException(barcode);
        }
        Store store = stores.findById(storeId).orElseThrow();
        Supplier supplier = suppliers.findOrCreate(store, request.supplierName());
        return products.save(Product.create(request, supplier, store));
    }

    /** UPDATE: finds the product in the store, validates, checks the barcode, then saves. */
    public Product updateProduct(long storeId, long productId, ProductRequest request) {
        Product product = getProduct(storeId, productId);
        Product.validate(request);
        String barcode = request.barcode().trim();
        if (products.existsByStore_StoreIdAndBarcodeAndProductIdNot(storeId, barcode, productId)) {
            throw new DuplicateBarcodeException(barcode);
        }
        Supplier supplier = suppliers.findOrCreate(product.getStore(), request.supplierName());
        product.updateDetails(request, supplier);
        return products.save(product);
    }

    /** DELETE: removes the product's row (only if it belongs to the store). */
    public void deleteProduct(long storeId, long productId) {
        products.delete(getProduct(storeId, productId));
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
