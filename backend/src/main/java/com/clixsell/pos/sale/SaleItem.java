package com.clixsell.pos.sale;

import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Map;

/**
 * One line of an invoice (report: SaleItem class, SALE_ITEMS table).
 *
 * <p>The product's name, barcode and selling price are copied when the line is
 * created, so a receipt stays correct even if the product changes later.
 */
@Entity
@Table(name = "sale_items")
public class SaleItem {

    public static final int MAX_QUANTITY = 10_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sale_item_id")
    private Long saleItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    /** Null if the product was deleted after the sale. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "product_name", nullable = false)
    private String productName;

    private String barcode;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private double unitPrice;

    @Column(name = "line_total", nullable = false)
    private double lineTotal;

    @Column(name = "returned_quantity", nullable = false)
    private int returnedQuantity;

    /**
     * Purchase cost per unit copied from the product when the sale is completed
     * (so later cost changes never change past profit). Null = cost not known.
     */
    @Column(name = "unit_cost")
    private Double unitCost;

    // ── V7, feature 6: piece / carton / box ──────────────────────────────────────
    // A package line counts PACKAGES in quantity; stock moves quantity × unitsPerPackage
    // base units. unit_price (and unit_cost) are per package, so line totals, revenue and
    // profit need no special case. Name and size are a snapshot (fixed by the database).

    @Column(name = "package_id")
    private Long packageId;

    @Column(name = "package_name")
    private String packageName;

    @Column(name = "units_per_package", nullable = false)
    private int unitsPerPackage = 1;

    @Column(name = "unit_price_minor")
    private Long unitPriceMinor;

    protected SaleItem() {
    }

    static SaleItem of(Sale sale, Product product, int quantity) {
        return of(sale, product, quantity, null);
    }

    static SaleItem of(Sale sale, Product product, int quantity, PackageSnapshot pkg) {
        SaleItem item = new SaleItem();
        item.sale = sale;
        item.product = product;
        item.productName = product.getName();
        item.barcode = product.getBarcode();
        item.unitPrice = pkg == null ? product.getSellingPrice() : pkg.price();
        if (pkg != null) {
            item.packageId = pkg.packageId();
            item.packageName = pkg.name();
            item.unitsPerPackage = pkg.unitsPerPackage();
        }
        item.unitPriceMinor = java.math.BigDecimal.valueOf(item.unitPrice).movePointRight(2)
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        item.updateQuantity(quantity);
        return item;
    }

    public Long getPackageId() { return packageId; }
    public String getPackageName() { return packageName; }
    public int getUnitsPerPackage() { return unitsPerPackage; }

    /** Base units (pieces) this line takes from stock. */
    public int getBaseUnits() {
        return quantity * unitsPerPackage;
    }

    /** Sets the quantity (1 – 10,000) and recalculates the line total. */
    public void updateQuantity(int newQuantity) {
        validateQuantity(newQuantity);
        this.quantity = newQuantity;
        calculateLineTotal();
    }

    /** Line total = unit price × quantity. */
    public void calculateLineTotal() {
        this.lineTotal = Money.round(Money.of(unitPrice).multiply(java.math.BigDecimal.valueOf(quantity)));
    }

    static void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1 || quantity > MAX_QUANTITY) {
            throw new ValidationException(Map.of("quantity",
                    "Quantity must be between 1 and " + MAX_QUANTITY));
        }
    }

    /** Copies the product's current purchase cost onto this line (at sale completion). */
    void recordUnitCost() {
        // per sold unit of THIS line: a package costs its pieces
        Double c = product == null ? null : product.getCostPrice();
        this.unitCost = c == null ? null
                : Money.round(Money.of(c).multiply(java.math.BigDecimal.valueOf(unitsPerPackage)));
    }

    public Double getUnitCost() {
        return unitCost;
    }

    /** How many units can still be returned. */
    public int getReturnableQuantity() {
        return quantity - returnedQuantity;
    }

    /** Called by a return: counts the returned units. */
    public void recordReturn(int units) {
        if (units < 1 || units > getReturnableQuantity()) {
            throw new IllegalArgumentException("Cannot return " + units + " units");
        }
        returnedQuantity += units;
    }

    public Long getSaleItemId() { return saleItemId; }
    public Sale getSale() { return sale; }
    public Product getProduct() { return product; }
    public String getProductName() { return productName; }
    public String getBarcode() { return barcode; }
    public int getQuantity() { return quantity; }
    public double getUnitPrice() { return unitPrice; }
    public double getLineTotal() { return lineTotal; }
    public int getReturnedQuantity() { return returnedQuantity; }
}
