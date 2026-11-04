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

    /** Required by JPA. */
    protected Supplier() {
    }

    public Supplier(String name, Store store) {
        this.name = name.trim();
        this.store = store;
    }

    public Long getSupplierId() { return supplierId; }
    public Store getStore() { return store; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getWhatsappNumber() { return whatsappNumber; }
    public String getAddress() { return address; }
}
