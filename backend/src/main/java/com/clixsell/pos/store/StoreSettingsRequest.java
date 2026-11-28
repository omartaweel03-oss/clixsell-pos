package com.clixsell.pos.store;

/**
 * Settings a Store Admin may change for their own store (Settings screen,
 * report Figure 3.9 – Taxes and General): tax rate, maximum discount, address
 * and phone. The store name stays with the System Admin.
 *
 * <p>Only the values sent change: a missing (null) value keeps the current one
 * (the Taxes dialog sends only tax and discount, General only address and
 * phone). A blank address or phone clears it.
 *
 * <p>{@code requireCashShift} (D2): mandatory cash shifts for this store; {@code cashRoundingMinor} (D4):
 * cash rounding step in agorot (0, 5, 10, 50, 100). Null keeps the current value.
 */
public record StoreSettingsRequest(String address, String phone, Double taxRate, Double maxDiscountPercent,
                                   Boolean requireCashShift, Integer cashRoundingMinor) {
}
