package com.clixsell.pos.common;

/**
 * Status of a store or a user account. Stores and users are deactivated,
 * never deleted, so their history is kept.
 */
public enum AccountStatus {
    ACTIVE,
    INACTIVE
}
