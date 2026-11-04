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

    protected SaleItem() {
    }

    static SaleItem of(Sale sale, Product product, int quantity) {
        SaleItem item = new SaleItem();
        item.sale = sale;
        item.product = product;
        item.productName = product.getName();
        item.barcode = product.getBarcode();
        item.unitPrice = product.getSellingPrice();
        item.updateQuantity(quantity);
        return item;
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
