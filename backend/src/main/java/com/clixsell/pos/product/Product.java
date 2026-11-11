package com.clixsell.pos.product;

import com.clixsell.pos.common.LocalDateTimeTextConverter;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.supplier.Supplier;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Product entity AND Product business logic.
 *
 * <p>Follows the Product class of the project class diagram (report, Figure 3.12):
 * productId, name, barcode, category, sellingPrice, quantity, minStock and the
 * operations updateStock(), isLowStock() and generateBarcode().
 *
 * <p>All product rules live here, so the API and the database layer never
 * decide on their own what a valid product is:
 * <ul>
 *   <li>{@link #validate(ProductRequest)} – the rules for every field</li>
 *   <li>{@link #create} / {@link #updateDetails} – build or change a product (validated)</li>
 *   <li>{@link #updateStock(int)} – change the quantity, never below zero</li>
 *   <li>{@link #isOutOfStock()}, {@link #isLowStock()}, {@link #getStockStatus()}</li>
 *   <li>{@link #getStockValue()} – price × quantity</li>
 *   <li>{@link #generateBarcode(Random)} – valid EAN-13 barcode</li>
 * </ul>
 *
 * <p>Every product belongs to one store (column store_id). Its barcode is
 * unique within that store, and its supplier must belong to the same store.
 */
@Entity
@Table(name = "products")
public class Product {

    // ── Business constants ────────────────────────────────────────────────
    /** Allowed categories (same keys as the Flutter app's ProductCategory enum). */
    public static final Set<String> CATEGORIES = Set.of(
            "dairy", "bakery", "beverages", "snacks", "groceries",
            "freshProduce", "frozen", "cleaning", "personalCare", "other");

    public static final int MAX_NAME_LENGTH = 80;
    public static final int MAX_SUPPLIER_LENGTH = 80;
    public static final int MAX_QUANTITY = 1_000_000;
    public static final double MAX_PRICE = 1_000_000;
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    public static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    // ── Columns (table: products) ─────────────────────────────────────────
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(nullable = false)
    private String name;

    /** Unique within the store: UNIQUE (store_id, barcode). */
    @Column(nullable = false)
    private String barcode;

    @Column(nullable = false)
    private String category;

    @Column(name = "selling_price", nullable = false)
    private double sellingPrice;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "min_stock", nullable = false)
    private int minStock;

    /** Purchase cost per unit (report ERD); null = not known. Only Store Admins see it. */
    @Column(name = "cost_price")
    private Double costPrice;

    @Column(name = "image", columnDefinition = "BLOB")
    private byte[] image;

    @Column(name = "image_name")
    private String imageName;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime updatedAt;

    /** Required by JPA. Use {@link #create} instead. */
    protected Product() {
    }

    // ── Creating and changing a product ───────────────────────────────────

    /**
     * Creates a new, valid product for {@code store}.
     *
     * @throws ProductValidationException if any field breaks a rule
     */
    public static Product create(ProductRequest request, Supplier supplier, Store store) {
        validate(request);
        Product product = new Product();
        product.store = store;
        product.applyDetails(request, supplier);
        product.createdAt = now();
        product.updatedAt = product.createdAt;
        return product;
    }

    /**
     * Replaces this product's details with the request's values.
     *
     * @throws ProductValidationException if any field breaks a rule
     */
    public void updateDetails(ProductRequest request, Supplier supplier) {
        validate(request);
        applyDetails(request, supplier);
        updatedAt = now();
    }

    private void applyDetails(ProductRequest request, Supplier newSupplier) {
        if (!sameStore(newSupplier.getStore(), store)) {
            throw new IllegalArgumentException("The supplier belongs to a different store");
        }
        this.name = request.name().trim();
        this.barcode = request.barcode().trim();
        this.category = request.category();
        this.sellingPrice = request.sellingPrice();
        this.quantity = request.quantity();
        this.minStock = request.minStock();
        this.costPrice = request.costPrice();
        this.supplier = newSupplier;
        if (request.image() != null && request.image().length > 0) {
            this.image = request.image();
            this.imageName = request.imageName();
        } else {
            this.image = null;
            this.imageName = null;
        }
    }

    /**
     * Changes the stock by {@code change} units (positive = received, negative = sold).
     *
     * @throws ProductValidationException if the result would be below zero or too large
     */
    public void updateStock(int change) {
        long newQuantity = (long) quantity + change;
        if (newQuantity < 0) {
            throw new ProductValidationException(Map.of("quantity",
                    "Not enough stock: only " + quantity + " available"));
        }
        if (newQuantity > MAX_QUANTITY) {
            throw new ProductValidationException(Map.of("quantity", "Quantity is too large"));
        }
        quantity = (int) newQuantity;
        updatedAt = now();
    }

    /** True if this product belongs to the store with this id. */
    public boolean belongsTo(long storeId) {
        return store != null && store.getStoreId() != null && store.getStoreId() == storeId;
    }

    private static boolean sameStore(Store a, Store b) {
        if (a == b) return true;
        return a != null && b != null && a.getStoreId() != null && a.getStoreId().equals(b.getStoreId());
    }

    // ── Stock rules ───────────────────────────────────────────────────────

    /** Out of stock: quantity is zero. */
    public boolean isOutOfStock() {
        return quantity <= 0;
    }

    /** Low stock: quantity is above zero but at or below the minimum stock level. */
    public boolean isLowStock() {
        return quantity > 0 && quantity <= minStock;
    }

    public StockStatus getStockStatus() {
        if (isOutOfStock()) return StockStatus.OUT_OF_STOCK;
        if (isLowStock()) return StockStatus.LOW_STOCK;
        return StockStatus.IN_STOCK;
    }

    /** Value of this product in the inventory: selling price × quantity. */
    public double getStockValue() {
        return sellingPrice * quantity;
    }

    // ── Validation ────────────────────────────────────────────────────────

    /**
     * Checks every field of a product request.
     *
     * @throws ProductValidationException listing every invalid field
     */
    public static void validate(ProductRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (r == null) {
            throw new ProductValidationException(Map.of("product", "Product data is required"));
        }

        String name = trim(r.name());
        if (name.isEmpty()) errors.put("name", "Name is required");
        else if (name.length() < 2) errors.put("name", "Name must be at least 2 characters");
        else if (name.length() > MAX_NAME_LENGTH) errors.put("name", "Name must be " + MAX_NAME_LENGTH + " characters or fewer");

        String barcode = trim(r.barcode());
        if (barcode.isEmpty()) errors.put("barcode", "Barcode is required");
        else if (!barcode.matches("\\d+")) errors.put("barcode", "Barcode must contain digits only");
        else if (barcode.length() < 8 || barcode.length() > 14) errors.put("barcode", "Barcode must be 8 to 14 digits");

        if (r.category() == null || r.category().isBlank()) errors.put("category", "Category is required");
        else if (!CATEGORIES.contains(r.category())) errors.put("category", "Unknown category: " + r.category());

        Double price = r.sellingPrice();
        if (price == null) errors.put("sellingPrice", "Price is required");
        else if (price.isNaN() || price.isInfinite()) errors.put("sellingPrice", "Price must be a number");
        else if (price <= 0) errors.put("sellingPrice", "Price must be greater than 0");
        else if (price > MAX_PRICE) errors.put("sellingPrice", "Price is too large");
        else if (BigDecimal.valueOf(price).stripTrailingZeros().scale() > 2) errors.put("sellingPrice", "Price can have at most 2 decimal places");

        // Purchase cost (optional): unknown when empty.
        Double cost = r.costPrice();
        if (cost != null) {
            if (cost.isNaN() || cost.isInfinite()) errors.put("costPrice", "Cost price must be a number");
            else if (cost < 0) errors.put("costPrice", "Cost price cannot be negative");
            else if (cost > MAX_PRICE) errors.put("costPrice", "Cost price is too large");
            else if (BigDecimal.valueOf(cost).stripTrailingZeros().scale() > 2) {
                errors.put("costPrice", "Cost price can have at most 2 decimal places");
            }
        }

        checkWholeNumber("quantity", "Quantity", r.quantity(), errors);
        checkWholeNumber("minStock", "Minimum stock level", r.minStock(), errors);

        String supplier = trim(r.supplierName());
        if (supplier.isEmpty()) errors.put("supplierName", "Supplier is required");
        else if (supplier.length() < 2) errors.put("supplierName", "Supplier must be at least 2 characters");
        else if (supplier.length() > MAX_SUPPLIER_LENGTH) errors.put("supplierName", "Supplier must be " + MAX_SUPPLIER_LENGTH + " characters or fewer");

        if (r.image() != null && r.image().length > 0) {
            if (r.image().length > MAX_IMAGE_BYTES) {
                errors.put("image", "Image is larger than 5 MB");
            } else if (!IMAGE_EXTENSIONS.contains(extensionOf(r.imageName()))) {
                errors.put("image", "Image must be PNG, JPG, GIF or WEBP");
            }
        }

        if (!errors.isEmpty()) {
            throw new ProductValidationException(errors);
        }
    }

    private static void checkWholeNumber(String field, String label, Integer value, Map<String, String> errors) {
        if (value == null) errors.put(field, label + " is required");
        else if (value < 0) errors.put(field, label + " cannot be negative");
        else if (value > MAX_QUANTITY) errors.put(field, label + " is too large");
    }

    // ── Barcode generation ────────────────────────────────────────────────

    /**
     * Generates a valid 13-digit EAN-13 barcode with the prefix 729,
     * including the correct check digit.
     */
    public static String generateBarcode(Random random) {
        int[] digits = new int[12];
        digits[0] = 7;
        digits[1] = 2;
        digits[2] = 9;
        for (int i = 3; i < 12; i++) {
            digits[i] = random.nextInt(10);
        }
        int sum = 0;
        StringBuilder code = new StringBuilder(13);
        for (int i = 0; i < 12; i++) {
            sum += digits[i] * (i % 2 == 0 ? 1 : 3);
            code.append(digits[i]);
        }
        int check = (10 - sum % 10) % 10;
        return code.append(check).toString();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().withNano(0);
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public Long getProductId() { return productId; }
    public Store getStore() { return store; }
    public Supplier getSupplier() { return supplier; }
    public String getName() { return name; }
    public String getBarcode() { return barcode; }
    public String getCategory() { return category; }
    public double getSellingPrice() { return sellingPrice; }
    public Double getCostPrice() { return costPrice; }
    public int getQuantity() { return quantity; }
    public int getMinStock() { return minStock; }
    public byte[] getImage() { return image; }
    public String getImageName() { return imageName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
