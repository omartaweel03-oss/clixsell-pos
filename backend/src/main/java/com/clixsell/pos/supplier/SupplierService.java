package com.clixsell.pos.supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Product Screen sends the supplier's name. This service turns that name
 * into a row in the suppliers table, so products can reference it by id.
 */
@Service
@Transactional
public class SupplierService {

    private final SupplierRepository suppliers;

    public SupplierService(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    /** Returns the supplier with this name (ignoring case), creating it if needed. */
    public Supplier findOrCreate(String name) {
        String trimmed = name.trim();
        return suppliers.findByNameIgnoreCase(trimmed)
                .orElseGet(() -> suppliers.save(new Supplier(trimmed)));
    }
}
