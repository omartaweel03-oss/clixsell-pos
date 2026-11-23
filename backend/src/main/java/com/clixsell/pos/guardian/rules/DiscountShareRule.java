package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.Amounts;
import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.ShareLineFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Ref;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.InvoiceCostStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G2c – allocation-based product margin risk (management decision U4).
 *
 * <p>A sold line with a recorded SALE-TIME cost whose share of the invoice
 * discount brings its revenue below that cost, even though the invoice as a
 * whole is not a loss. The share uses the same rule as the Reports profit:
 * <pre>
 * line revenue after discount = line_total × (subtotal − discount) ÷ subtotal   (tax excluded)
 * </pre>
 * <ul>
 *   <li>MEDIUM. A product-margin risk based on the allocation, NOT a confirmed
 *       invoice loss (an invoice that is a confirmed loss is G2b only).</li>
 *   <li>Only lines with a recorded cost; the cost of other lines is never guessed
 *       (an invoice with other lines without cost still qualifies: the share
 *       does not need their cost).</li>
 *   <li>Lines priced below cost before the discount are G2a, not G2c.</li>
 *   <li>Sale-time fact: units returned later are shown, never subtracted.</li>
 *   <li>One alert per product per day, like G2a.</li>
 * </ul>
 */
public final class DiscountShareRule implements GuardianRule {

    @Override
    public List<Alert> evaluate(GuardianData d) {
        Map<Long, InvoiceFact> invoices = new HashMap<>();
        for (InvoiceFact i : d.invoices()) invoices.put(i.saleId(), i);
        Map<String, List<ShareLineFact>> groups = new LinkedHashMap<>();
        Map<ShareLineFact, BigDecimal> revenue = new HashMap<>();
        for (ShareLineFact l : d.discountLines()) {
            InvoiceFact inv = invoices.get(l.saleId());
            if (inv == null || InvoiceCostStatus.of(inv) == InvoiceCostStatus.CONFIRMED_LOSS) continue; // G2b
            BigDecimal price = Amounts.money(l.unitPrice());
            BigDecimal unitCost = Amounts.money(l.unitCost());
            if (price.compareTo(unitCost) < 0) continue; // G2a
            BigDecimal share = BigDecimal.valueOf(l.lineTotal())
                    .multiply(BigDecimal.valueOf(l.subtotal() - l.discount()))
                    .divide(BigDecimal.valueOf(l.subtotal()), 6, RoundingMode.HALF_UP);
            BigDecimal cost = unitCost.multiply(BigDecimal.valueOf(l.quantity()));
            if (share.setScale(2, RoundingMode.HALF_UP).compareTo(cost) >= 0) continue;
            revenue.put(l, share);
            String product = l.productId() != null ? l.productId().toString() : "name:" + l.productName();
            groups.computeIfAbsent(product + ":" + l.completedAt().toLocalDate(), k -> new ArrayList<>()).add(l);
        }
        List<Alert> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<ShareLineFact> lines = e.getValue();
            ShareLineFact first = lines.get(0);
            // The numbers shown are those of the line with the largest shortfall per unit.
            ShareLineFact worst = first;
            BigDecimal worstGap = null;
            for (ShareLineFact l : lines) {
                BigDecimal gap = Amounts.money(l.unitCost())
                        .subtract(revenue.get(l).divide(BigDecimal.valueOf(l.quantity()), 6, RoundingMode.HALF_UP));
                if (worstGap == null || gap.compareTo(worstGap) > 0) {
                    worst = l;
                    worstGap = gap;
                }
            }
            int units = 0, returned = 0;
            BigDecimal shortfall = BigDecimal.ZERO;
            LocalDateTime last = first.completedAt();
            LinkedHashSet<Ref> refs = new LinkedHashSet<>();
            List<String> numbers = new ArrayList<>();
            for (ShareLineFact l : lines) {
                units += l.quantity();
                returned += l.returnedQuantity();
                shortfall = shortfall.add(Amounts.money(l.unitCost()).multiply(BigDecimal.valueOf(l.quantity()))
                        .subtract(revenue.get(l)));
                if (l.completedAt().isAfter(last)) last = l.completedAt();
                if (refs.add(GuardianRule.sale(l.saleId(), l.invoiceNumber()))) numbers.add(l.invoiceNumber());
            }
            BigDecimal unitRevenue = revenue.get(worst).divide(BigDecimal.valueOf(worst.quantity()), 2, RoundingMode.HALF_UP);
            BigDecimal discountPercent = Amounts.percent(Amounts.money(worst.discount()), Amounts.money(worst.subtotal()))
                    .setScale(2, RoundingMode.HALF_UP);
            List<Ref> evidence = new ArrayList<>(refs);
            if (first.productId() != null) evidence.add(GuardianRule.product(first.productId(), first.productName()));
            boolean exists = d.product(first.productId()).isPresent();
            out.add(GuardianRule.alert("G2c:product:" + e.getKey(), "G2c", "DISCOUNT_SHARE_BELOW_COST", Severity.MEDIUM,
                    "PROFIT",
                    params("productName", first.productName(), "times", lines.size(), "units", units,
                            "unitPrice", worst.unitPrice(), "unitCost", worst.unitCost(),
                            "allocatedUnitRevenue", unitRevenue.doubleValue(),
                            "discountPercent", discountPercent.doubleValue(),
                            "shortfall", shortfall.setScale(2, RoundingMode.HALF_UP).doubleValue(),
                            "unitsReturned", returned, "invoices", numbers, "day", last.toLocalDate().toString()),
                    evidence, null, null, last, exists ? GuardianRule.action("PRODUCT", first.productId()) : null));
        }
        return out;
    }
}
