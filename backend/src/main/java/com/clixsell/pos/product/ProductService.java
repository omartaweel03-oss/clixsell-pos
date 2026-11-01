package com.clixsell.pos.product;

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
 * the rules that need the database (barcode must be unique, product must
 * exist) and runs each operation in a transaction.
 */
@Service
@Transactional
public class ProductService {

    private final ProductRepository products;
    private final SupplierService suppliers;
    private final Random random = new SecureRandom();

    public ProductService(ProductRepository products, SupplierService suppliers) {
        this.products = products;
        this.suppliers = suppliers;
    }

    @Transactional(readOnly = true)
    public List<Product> listProducts() {
        return products.findAllByOrderByProductIdDesc();
    }

    @Transactional(readOnly = true)
    public Product getProduct(long productId) {
        return products.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /** INSERT: validates, checks the barcode is unique, then saves a new row. */
    public Product createProduct(ProductRequest request) {
        Product.validate(request);
        String barcode = request.barcode().trim();
        if (products.existsByBarcode(barcode)) {
            throw new DuplicateBarcodeException(barcode);
        }
        Supplier supplier = suppliers.findOrCreate(request.supplierName());
        Product product = Product.create(request, supplier);
        return products.save(product);
    }

    /** UPDATE: finds the product, validates, checks the barcode, then saves the changes. */
    public Product updateProduct(long productId, ProductRequest request) {
        Product product = getProduct(productId);
        Product.validate(request);
        String barcode = request.barcode().trim();
        if (products.existsByBarcodeAndProductIdNot(barcode, productId)) {
            throw new DuplicateBarcodeException(barcode);
        }
        Supplier supplier = suppliers.findOrCreate(request.supplierName());
        product.updateDetails(request, supplier);
        return products.save(product);
    }

    /** DELETE: removes the product's row. */
    public void deleteProduct(long productId) {
        Product product = getProduct(productId);
        products.delete(product);
    }

    /** Generates a barcode that no product uses yet. */
    @Transactional(readOnly = true)
    public String generateUniqueBarcode() {
        String code;
        do {
            code = Product.generateBarcode(random);
        } while (products.existsByBarcode(code));
        return code;
    }
}
