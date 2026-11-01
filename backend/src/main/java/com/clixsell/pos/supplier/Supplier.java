package com.clixsell.pos.supplier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Supplier entity (table: suppliers), from the SUPPLIERS table of the ERD.
 * Each product references one supplier through products.supplier_id.
 */
@Entity
@Table(name = "suppliers")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(nullable = false, unique = true)
    private String name;

    private String phone;

    @Column(name = "whatsapp_number")
    private String whatsappNumber;

    private String address;

    /** Required by JPA. */
    protected Supplier() {
    }

    public Supplier(String name) {
        this.name = name.trim();
    }

    public Long getSupplierId() { return supplierId; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getWhatsappNumber() { return whatsappNumber; }
    public String getAddress() { return address; }
}
