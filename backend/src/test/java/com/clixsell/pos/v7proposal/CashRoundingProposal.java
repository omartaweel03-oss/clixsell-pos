package com.clixsell.pos.v7proposal;

/**
 * PROPOSAL ONLY — the cash rounding and refund-allocation rules (D4, requirement 7), in integer minor
 * units (agorot), for validation before V7 is approved.
 *
 * <p>Rules:
 * <ul>
 *   <li>Rounding applies to CASH only; step 0 = no rounding (default). Steps: 5, 10, 50, 100.</li>
 *   <li>Nearest multiple of the step; an exact midpoint rounds UP (half-up), e.g. 19.95 → 20.00 with a
 *       0.10 step. The invoice total (incl. tax) is never changed: the adjustment is stored apart
 *       (sales.rounding_minor) and payable = total + adjustment.</li>
 *   <li>Refunds: each return's exact share is computed from the original sale (as today); a CASH refund
 *       is rounded to the same step once, and the LAST return of the invoice pays exactly what is left
 *       of the amount paid. The sum of all refunds always equals the amount paid, never more; rounding is
 *       never applied twice.</li>
 * </ul>
 */
public final class CashRoundingProposal {

    private CashRoundingProposal() {
    }

    /** Nearest multiple of {@code step}, midpoint up. step 0 = unchanged. */
    public static long roundToStep(long minor, int step) {
        if (step == 0) return minor;
        if (step < 0 || minor < 0) throw new IllegalArgumentException("negative");
        long q = minor / step, r = minor % step;
        return (2 * r >= step ? q + 1 : q) * step;
    }

    /** The adjustment stored with the sale: payable − total (0 for non-cash). */
    public static long adjustment(long totalMinor, int step, boolean cash) {
        return cash ? roundToStep(totalMinor, step) - totalMinor : 0;
    }

    /**
     * Cash paid back for one return.
     *
     * @param exactShareMinor the return's exact share of the invoice total (existing calculateRefund)
     * @param paidMinor       what the customer paid for the invoice (total + adjustment)
     * @param refundedMinor   what earlier returns of the same invoice already paid back
     * @param lastReturn      true when this return makes every unit of the invoice returned
     */
    public static long cashRefund(long exactShareMinor, int step, long paidMinor, long refundedMinor, boolean lastReturn) {
        long left = paidMinor - refundedMinor;
        if (left < 0) throw new IllegalStateException("over-refunded");
        if (lastReturn) return left;
        return Math.min(roundToStep(exactShareMinor, step), left);
    }
}
