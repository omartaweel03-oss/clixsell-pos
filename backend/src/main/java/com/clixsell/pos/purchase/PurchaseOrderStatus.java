package com.clixsell.pos.purchase;

import java.util.Set;

/**
 * Purchase-order states (report Figure 3.20, decisions D8 / D18). In Phase 2 an order can
 * only reach PENDING_APPROVAL, APPROVED, REJECTED and CANCELED; the later states exist in
 * the database for Phase 3 and are never set by this version.
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
