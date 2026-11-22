package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.Amounts;
import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.LogFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Ref;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.GuardianThresholds;
import com.clixsell.pos.guardian.InvoiceCostStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G1 – high discount (report p.18–19 "high discounts", Figure 3.1).
 *
 * <p>A completed invoice is a high discount when BOTH:
 * <ul>
 *   <li>discount ÷ subtotal × 100 ≥ 75% of the store's maximum discount, and</li>
 *   <li>the discount is at least 5 ₪.</li>
 * </ul>
 * The maximum is the one saved in the log when the sale was completed; for an
 * older sale (before the log) it is the store's current maximum, and the alert
 * says so. A store whose maximum is 0 allows no discount, so the rule is off.
 *
 * <p>Severity MEDIUM. Three or more by the SAME logged user on the same day give
 * one HIGH alert instead. The user is the one who completed the sale, from the
 * activity log; without a log row no name is shown. An invoice that is also a
 * confirmed loss gets only the G2b alert (with its discount), never two alerts.
 * The discount itself is allowed by the store: the alert means "please review".
 */
public final class HighDiscountRule implements GuardianRule {

    /** A high-discount invoice with the numbers it was judged by. */
    public record Evaluation(InvoiceFact invoice, LogFact log, BigDecimal percent, double maxPercent,
                             String maxSource, BigDecimal threshold) {
    }

    /** The evaluation if the invoice is a high discount, else null. */
    public static Evaluation check(InvoiceFact i, LogFact log, double currentMaxPercent) {
        if (i.discount() <= 0 || i.subtotal() <= 0) return null;
        Double atSale = log == null ? null : log.number("maxPercentAtSale");
        double max = atSale != null ? atSale : currentMaxPercent;
        if (max <= 0) return null;
        BigDecimal discount = Amounts.money(i.discount());
        BigDecimal percent = Amounts.percent(discount, Amounts.money(i.subtotal()));
        BigDecimal threshold = BigDecimal.valueOf(max).multiply(GuardianThresholds.HIGH_DISCOUNT_SHARE_OF_MAX)
                .setScale(4, RoundingMode.HALF_UP);
        if (percent.compareTo(threshold) < 0) return null;
        if (discount.compareTo(GuardianThresholds.HIGH_DISCOUNT_MIN_AMOUNT) < 0) return null;
        return new Evaluation(i, log, percent, max, atSale != null ? "AT_SALE" : "CURRENT", threshold);
    }

    /** Every high-discount invoice of the period. */
    public static List<Evaluation> all(GuardianData d) {
        List<Evaluation> out = new ArrayList<>();
        for (InvoiceFact i : d.invoices()) {
            Evaluation e = check(i, d.discountLogs().get(i.saleId()), d.maxDiscountPercent());
            if (e != null) out.add(e);
        }
        return out;
    }

    @Override
    public List<Alert> evaluate(GuardianData d) {
        List<Evaluation> all = all(d);
        // Same logged user, same day.
        Map<String, List<Evaluation>> byUserDay = new LinkedHashMap<>();
        for (Evaluation e : all) {
            if (e.log() == null) continue;
            byUserDay.computeIfAbsent(e.log().userId() + ":" + e.invoice().completedAt().toLocalDate(),
                    k -> new ArrayList<>()).add(e);
        }
        List<Alert> out = new ArrayList<>();
        Set<Long> grouped = new HashSet<>();
        for (List<Evaluation> group : byUserDay.values()) {
            if (group.size() < GuardianThresholds.HIGH_DISCOUNT_REPEAT) continue;
            LogFact first = group.get(0).log();
            LocalDate day = group.get(0).invoice().completedAt().toLocalDate();
            BigDecimal totalDiscount = BigDecimal.ZERO;
            List<Ref> refs = new ArrayList<>();
            List<String> numbers = new ArrayList<>();
            var last = group.get(0).invoice().completedAt();
            for (Evaluation e : group) {
                grouped.add(e.invoice().saleId());
                totalDiscount = totalDiscount.add(Amounts.money(e.invoice().discount()));
                refs.add(GuardianRule.sale(e.invoice().saleId(), e.invoice().invoiceNumber()));
                numbers.add(e.invoice().invoiceNumber());
                if (e.invoice().completedAt().isAfter(last)) last = e.invoice().completedAt();
            }
            out.add(GuardianRule.alert("G1:user:" + first.userId() + ":" + day, "G1", "HIGH_DISCOUNT_REPEATED",
                    Severity.HIGH, "DISCOUNT",
                    params("count", group.size(), "repeatLimit", GuardianThresholds.HIGH_DISCOUNT_REPEAT,
                            "totalDiscount", totalDiscount.doubleValue(), "invoices", numbers,
                            "sharePercent", 75, "minAmount", GuardianThresholds.HIGH_DISCOUNT_MIN_AMOUNT.doubleValue()),
                    refs, GuardianRule.actor(first), null, last, GuardianRule.action("ACTIVITY", null)));
        }
        for (Evaluation e : all) {
            InvoiceFact i = e.invoice();
            if (grouped.contains(i.saleId())) continue;
            if (InvoiceCostStatus.of(i) == InvoiceCostStatus.CONFIRMED_LOSS) continue; // G2b covers it
            String startedBy = e.log() != null && e.log().userId() != i.startedByUserId() ? i.startedByName() : null;
            out.add(GuardianRule.alert("G1:sale:" + i.saleId(), "G1", "HIGH_DISCOUNT", Severity.MEDIUM, "DISCOUNT",
                    params("invoiceNumber", i.invoiceNumber(), "subtotal", i.subtotal(), "discount", i.discount(),
                            "percent", e.percent().setScale(2, RoundingMode.HALF_UP).doubleValue(),
                            "maxPercent", e.maxPercent(), "maxSource", e.maxSource(),
                            "thresholdPercent", e.threshold().setScale(2, RoundingMode.HALF_UP).doubleValue(),
                            "sharePercent", 75, "minAmount", GuardianThresholds.HIGH_DISCOUNT_MIN_AMOUNT.doubleValue(),
                            "total", i.total(), "startedBy", startedBy),
                    List.of(GuardianRule.sale(i.saleId(), i.invoiceNumber())),
                    GuardianRule.actor(e.log()), e.log() == null ? NOT_RECORDED : null, i.completedAt(),
                    GuardianRule.action("INVOICE", i.saleId())));
        }
        return out;
    }
}
