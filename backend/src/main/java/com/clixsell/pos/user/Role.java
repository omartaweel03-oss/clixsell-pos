package com.clixsell.pos.user;

/**
 * What a user may do. Checked by the backend on every request.
 *
 * <ul>
 *   <li>SYSTEM_ADMIN – manages stores and their Store Admins; belongs to no store.</li>
 *   <li>STORE_ADMIN  – the report's "Manager": manages the products and cashiers of one store.</li>
 *   <li>CASHIER      – works in one store; can only read that store's products.</li>
 * </ul>
 */
public enum Role {
    SYSTEM_ADMIN,
    STORE_ADMIN,
    CASHIER
}
