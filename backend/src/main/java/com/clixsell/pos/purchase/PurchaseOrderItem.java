package com.clixsell.pos.purchase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One line of a purchase order (table purchase_order_items, V6) with the server's snapshot of
 * the product and of the calculation. Only the quantity can change (while the order is
 * PENDING_APPROVAL). product_id / product_store_id are set to NULL by the database when the
 * product is deleted, and open_product_id (the reservation) is released by the database when
 * the order becomes final: Hibernate never writes those columns back.
 */
@Entity
@Table(name = "purchase_order_items")
public class PurchaseOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private Long storeId;

    @Column(name = "product_id", updatable = false)
    private Long productId;

    @Column(name = "product_store_id", updatable = false)
    private Long productStoreId;

    @Column(name = "product_name", nullable = false, updatable = false)
    private String productName;

    @Column(nullable = false, updatable = false)
    private String barcode;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "suggested_quantity", updatable = false)
    private Integer suggestedQuantity;

    @Column(nullable = false, updatable = false)
    private String basis;

    @Column(name = "calc_json", nullable = false, updatable = false)
    private String calcJson;

    @Column(name = "expected_unit_cost", updatable = false)
    private Double expectedUnitCost;

    @Column(name = "open_product_id", updatable = false)
    private Long openProductId;

    protected PurchaseOrderItem() {
    }

    PurchaseOrderItem(long orderId, long storeId, long productId, String productName, String barcode, int quantity,
                      Integer suggestedQuantity, String basis, String calcJson, Double expectedUnitCost) {
        this.orderId = orderId;
        this.storeId = storeId;
        this.productId = productId;
        this.productStoreId = storeId;
        this.productName = productName;
        this.barcode = barcode;
        this.quantity = quantity;
        this.suggestedQuantity = suggestedQuantity;
        this.basis = basis;
        this.calcJson = calcJson;
        this.expectedUnitCost = expectedUnitCost;
        this.openProductId = productId; // the reservation: one open order per product (V6 unique index)
    }

    void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Long getItemId() { return itemId; }
    public Long getOrderId() { return orderId; }
    public Long getStoreId() { return storeId; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getBarcode() { return barcode; }
    public int getQuantity() { return quantity; }
    public Integer getSuggestedQuantity() { return suggestedQuantity; }
    public String getBasis() { return basis; }
    public String getCalcJson() { return calcJson; }
    public Double getExpectedUnitCost() { return expectedUnitCost; }
    public Long getOpenProductId() { return openProductId; }
}
