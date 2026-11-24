package com.clixsell.pos.supplier;

import com.clixsell.pos.store.Store;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Supplier entity (table: suppliers), from the SUPPLIERS table of the ERD.
 * Each product references one supplier through products.supplier_id.
 * Every supplier belongs to one store; its name is unique within that store.
 *
 * <p>Suppliers are never deleted (the database refuses it since V6): products and
 * purchase orders keep pointing to them. A supplier that is no longer used is
 * deactivated; only its Store Admin reactivates it (Supplier Management screen).
 */
@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "supplier_id")
    private Long supplierId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false)
    private String name;

    private String phone;

    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    private String address;

    /** V6: true = can be chosen for new products and purchase orders. Existing rows start active. */
    @Column(nullable = false)
    private boolean active = true;

    /** Required by JPA. */
    protected Supplier() {
    }

    /** A new active supplier; the name must already be normalized ({@link SupplierNames#normalize}). */
    public Supplier(String name, Store store) {
        this.name = name;
        this.store = store;
    }

    /** Contact details from Supplier Management (already validated and normalized by the service). */
    void updateDetails(String name, String phone, String whatsappNumber, String address) {
        this.name = name;
        this.phone = phone;
        this.whatsappNumber = whatsappNumber;
        this.address = address;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public Long getSupplierId() { return supplierId; }
    public Store getStore() { return store; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getWhatsappNumber() { return whatsappNumber; }
    public String getAddress() { return address; }
    public boolean isActive() { return active; }
}
