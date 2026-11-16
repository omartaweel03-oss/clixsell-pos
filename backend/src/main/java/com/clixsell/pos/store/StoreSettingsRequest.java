package com.clixsell.pos.store;

/**
 * Settings a Store Admin may change for their own store (Settings screen,
 * report Figure 3.9 – Taxes and General): tax rate, maximum discount, address
 * and phone. The store name stays with the System Admin.
 *
 * <p>All four values are sent every time (the app sends the current value of
 * anything it does not change); a blank address or phone clears it.
 */
public record StoreSettingsRequest(String address, String phone, Double taxRate, Double maxDiscountPercent) {
}
