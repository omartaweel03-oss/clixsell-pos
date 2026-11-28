package com.clixsell.pos.returns;

import java.math.BigDecimal;

/**
 * Approved cash refund policy: the drawer does not hold enough recorded cash for this refund. Nothing
 * was saved. The Store Admin resolves it with a real, recorded cash-in (after physically adding money)
 * or the refund waits; no balance check is ever skipped. Returned as 409 DRAWER_INSUFFICIENT with the
 * available and required amounts.
 */
public class DrawerInsufficientException extends RuntimeException {

    private final long availableMinor;
    private final long requiredMinor;

    public DrawerInsufficientException(long availableMinor, long requiredMinor) {
        super("The drawer does not have enough cash for this refund: available "
                + BigDecimal.valueOf(availableMinor, 2).toPlainString() + ", needed "
                + BigDecimal.valueOf(requiredMinor, 2).toPlainString()
                + ". Ask the Store Admin to record a cash-in after adding money to the drawer.");
        this.availableMinor = availableMinor;
        this.requiredMinor = requiredMinor;
    }

    public long getAvailableMinor() { return availableMinor; }
    public long getRequiredMinor() { return requiredMinor; }
}
