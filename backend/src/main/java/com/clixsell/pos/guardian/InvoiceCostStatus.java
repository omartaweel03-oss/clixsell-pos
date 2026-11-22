package com.clixsell.pos.guardian;

import com.clixsell.pos.guardian.GuardianData.InvoiceFact;

import java.math.BigDecimal;

/**
 * Whether a completed invoice lost money (final plan, part 2).
 *
 * <ul>
 *   <li>Net revenue = subtotal − discount. Tax is not the store's money, so it is
 *       never part of it (the same rule as the Reports profit).</li>
 *   <li>Cost = Σ unit_cost × quantity, with the cost saved on each line AT SALE TIME.</li>
 *   <li>A loss is CONFIRMED only when every line has a cost. One line without a
 *       cost makes the invoice INCOMPLETE_COST: not a loss and not a profit, even
 *       if the known costs alone are higher than the revenue.</li>
 *   <li>The invoice is judged as it was sold; a later return does not change it.</li>
 * </ul>
 */
public enum InvoiceCostStatus {
    CONFIRMED_LOSS, NO_LOSS, INCOMPLETE_COST;

    public static InvoiceCostStatus of(InvoiceFact i) {
        if (i.linesWithoutCost() > 0) return INCOMPLETE_COST;
        return loss(i).compareTo(GuardianThresholds.MIN_LOSS) >= 0 ? CONFIRMED_LOSS : NO_LOSS;
    }

    public static BigDecimal netRevenue(InvoiceFact i) {
        return Amounts.money(i.subtotal()).subtract(Amounts.money(i.discount()));
    }

    public static BigDecimal cost(InvoiceFact i) {
        return Amounts.money(i.cost());
    }

    /** cost − net revenue (positive = money lost). Meaningful only when every line has a cost. */
    public static BigDecimal loss(InvoiceFact i) {
        return cost(i).subtract(netRevenue(i));
    }
}
