package com.clixsell.pos.store;

/** Store details sent by the System Admin to create or edit a store. */
public record StoreRequest(String name, String address, String phone) {
}
