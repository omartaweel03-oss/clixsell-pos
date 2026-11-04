package com.clixsell.pos.store;

/**
 * Store details sent by the System Admin to create or edit a store.
 * {@code taxRate} and {@code maxDiscountPercent} are optional: when null, a new
 * store gets the defaults (16% and 10%) and an existing store keeps its values.
 */
public record StoreRequest(String name, String address, String phone,
                           Double taxRate, Double maxDiscountPercent) {

    public StoreRequest(String name, String address, String phone) {
        this(name, address, phone, null, null);
    }
}
