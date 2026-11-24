package com.clixsell.pos.purchase;

import java.util.Set;

/**
 * Purchase-order states (report Figure 3.20, decisions D8 / D18). The allowed transitions are
 * enforced by the service and again by the database (V6 trigger T5).
 */
public enum PurchaseOrderStatus {
    PENDING_APPROVAL, APPROVED, REJECTED, CANCELED, MESSAGE_GENERATED, SENT, CONFIRMED, CLOSED;

    public static final Set<PurchaseOrderStatus> FINAL = Set.of(REJECTED, CANCELED, CLOSED);

    public boolean isFinal() {
        return FINAL.contains(this);
    }

    /** Holds its products' reservations (open order). */
    public boolean isOpen() {
        return !isFinal();
    }
}
