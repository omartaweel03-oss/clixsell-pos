package com.clixsell.pos.supplier;

import com.clixsell.pos.store.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Product Screen sends the supplier's name. This service turns that name
 * into a row in the suppliers table of the user's store, so products can
 * reference it by id. Two stores can have suppliers with the same name.
 */
@Service
@Transactional
public class SupplierService {

    private final SupplierRepository suppliers;

    public SupplierService(SupplierRepository suppliers) {
        this.suppliers = suppliers;
    }

    /** Returns the store's supplier with this name (ignoring case), creating it if needed. */
    public Supplier findOrCreate(Store store, String name) {
        String trimmed = name.trim();
        return suppliers.findByStore_StoreIdAndNameIgnoreCase(store.getStoreId(), trimmed)
                .orElseGet(() -> suppliers.save(new Supplier(trimmed, store)));
    }
}
