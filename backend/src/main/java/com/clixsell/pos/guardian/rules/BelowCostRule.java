package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.Amounts;
import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.LineFact;
import com.clixsell.pos.guardian.GuardianData.LogFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Ref;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.InvoiceCostStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G2 – profit risk in sales (report p.18–19 "potential profit loss", Figure 3.1
 * "Profit risks"). Only sale-time costs are used; a missing cost is never guessed.
 *
 * <ul>
 *   <li><b>G2a</b> – a line with a KNOWN sale-time cost sold at a unit price below
 *       that cost (before the invoice discount, which only makes it worse). One
 *       HIGH alert per product per day. This is about the product's price on that
 *       line, not about the whole invoice.</li>
 *   <li><b>G2b</b> – a CONFIRMED loss: every line of the invoice has a cost and
 *       (subtotal − discount) &lt; total cost by at least 0.01 ₪ (tax excluded).
 *       One HIGH alert per invoice. An invoice with any line without a cost is
 *       INCOMPLETE_COST and is never reported as a loss.</li>
 * </ul>
 * Both are SALE-TIME facts: units returned later are sent with the alert
 * ("unitsReturned") so the app says the loss happened at the sale and is not
 * the result after the return; returns never change the judgement.
 * <ul>
 * </ul>
 */
public final class BelowCostRule implements GuardianRule {

    @Override
    public List<Alert> evaluate(GuardianData d) {
        List<Alert> out = new ArrayList<>(belowCostLines(d));
        for (InvoiceFact i : d.invoices()) {
            if (InvoiceCostStatus.of(i) != InvoiceCostStatus.CONFIRMED_LOSS) continue;
            BigDecimal net = InvoiceCostStatus.netRevenue(i);
            BigDecimal cost = InvoiceCostStatus.cost(i);
            LogFact log = d.discountLogs().get(i.saleId());
            boolean discounted = i.discount() > 0;
            out.add(GuardianRule.alert("G2b:sale:" + i.saleId(), "G2b", "CONFIRMED_LOSS", Severity.HIGH, "PROFIT",
                    params("invoiceNumber", i.invoiceNumber(), "subtotal", i.subtotal(), "discount", i.discount(),
                            "netRevenue", net.doubleValue(), "cost", cost.doubleValue(),
                            "loss", cost.subtract(net).doubleValue(), "lines", i.lines(),
                            "unitsSold", i.unitsSold(), "unitsReturned", i.unitsReturned(),
                            "startedBy", log != null && log.userId() != i.startedByUserId() ? i.startedByName() : null,
                            "startedByRole", log != null && log.userId() != i.startedByUserId() ? i.startedByRole() : null),
                    List.of(GuardianRule.sale(i.saleId(), i.invoiceNumber())),
                    discounted ? GuardianRule.actor(log) : null,
                    discounted && log == null ? NOT_RECORDED : null,
                    i.completedAt(), GuardianRule.action("INVOICE", i.saleId())));
        }
        return out;
    }

    private static List<Alert> belowCostLines(GuardianData d) {
        Map<String, List<LineFact>> groups = new LinkedHashMap<>();
        for (LineFact l : d.belowCostLines()) {
            String product = l.productId() != null ? l.productId().toString() : "name:" + l.productName();
            groups.computeIfAbsent(product + ":" + l.completedAt().toLocalDate(), k -> new ArrayList<>()).add(l);
        }
        List<Alert> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<LineFact> lines = e.getValue();
            LineFact worst = lines.get(0);
            int units = 0, returned = 0;
            BigDecimal loss = BigDecimal.ZERO;
            LocalDateTime last = worst.completedAt();
            LinkedHashSet<Ref> sales = new LinkedHashSet<>();
            for (LineFact l : lines) {
                BigDecimal gap = Amounts.money(l.unitCost()).subtract(Amounts.money(l.unitPrice()));
                if (gap.compareTo(Amounts.money(worst.unitCost()).subtract(Amounts.money(worst.unitPrice()))) > 0) {
                    worst = l;
                }
                units += l.quantity();
                returned += l.returnedQuantity();
                loss = loss.add(gap.multiply(BigDecimal.valueOf(l.quantity())));
                if (l.completedAt().isAfter(last)) last = l.completedAt();
                sales.add(GuardianRule.sale(l.saleId(), l.invoiceNumber()));
            }
            LocalDate day = last.toLocalDate();
            Long productId = worst.productId();
            boolean exists = d.product(productId).isPresent();
            List<Ref> evidence = new ArrayList<>(sales);
            if (productId != null) evidence.add(GuardianRule.product(productId, worst.productName()));
            out.add(GuardianRule.alert("G2a:product:" + e.getKey(), "G2a", "BELOW_COST_SALE", Severity.HIGH, "PROFIT",
                    params("productName", worst.productName(), "times", lines.size(), "units", units,
                            "unitPrice", worst.unitPrice(), "unitCost", worst.unitCost(),
                            "lossBeforeDiscount", Amounts.round2(loss.doubleValue()), "day", day.toString(),
                            "unitsReturned", returned),
                    evidence, null, null, last,
                    exists ? GuardianRule.action("PRODUCT", productId) : null));
        }
        return out;
    }
}
