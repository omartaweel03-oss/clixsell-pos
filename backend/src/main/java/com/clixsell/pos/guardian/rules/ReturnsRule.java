package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.Amounts;
import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.ReturnFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Ref;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.GuardianThresholds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G8 – returns.
 *
 * <ul>
 *   <li><b>G8a</b> (per day): refunds ≥ 50 ₪ AND refunds ≥ 10% of that day's sales
 *       (completed totals, tax included like the refunds) → MEDIUM; ≥ 25% → HIGH.
 *       A day with no sales and refunds ≥ 50 ₪ → MEDIUM. The split by user uses
 *       returns.cashier_id, which the backend sets to the user who processed the return.</li>
 *   <li><b>G8b</b> (per product): the same product returned 3 or more times within
 *       7 days → MEDIUM ("maybe a problem with the product"); about the product, not a person.</li>
 * </ul>
 */
public final class ReturnsRule implements GuardianRule {

    public static final String RETURN_RECORD = "RETURN_RECORD";

    @Override
    public List<Alert> evaluate(GuardianData d) {
        List<Alert> out = new ArrayList<>(perDay(d));
        out.addAll(perProduct(d));
        return out;
    }

    private static List<Alert> perDay(GuardianData d) {
        Map<LocalDate, List<ReturnFact>> byDay = new TreeMap<>();
        for (ReturnFact r : d.returns()) byDay.computeIfAbsent(r.returnDate().toLocalDate(), k -> new ArrayList<>()).add(r);
        Map<LocalDate, BigDecimal> sales = new TreeMap<>();
        for (InvoiceFact i : d.invoices()) sales.merge(i.completedAt().toLocalDate(), Amounts.money(i.total()), BigDecimal::add);

        List<Alert> out = new ArrayList<>();
        for (var e : byDay.entrySet()) {
            List<ReturnFact> list = e.getValue();
            BigDecimal refunds = BigDecimal.ZERO;
            for (ReturnFact r : list) refunds = refunds.add(Amounts.money(r.refund()));
            if (refunds.compareTo(GuardianThresholds.RETURNS_MIN_AMOUNT) < 0) continue;
            BigDecimal daySales = sales.getOrDefault(e.getKey(), BigDecimal.ZERO);
            Severity sev;
            Double percent = null;
            if (daySales.signum() > 0) {
                BigDecimal p = Amounts.percent(refunds, daySales);
                percent = Amounts.round1(p.doubleValue());
                if (p.compareTo(BigDecimal.valueOf(GuardianThresholds.RETURNS_HIGH_PERCENT)) >= 0) sev = Severity.HIGH;
                else if (p.compareTo(BigDecimal.valueOf(GuardianThresholds.RETURNS_MEDIUM_PERCENT)) >= 0) sev = Severity.MEDIUM;
                else continue;
            } else {
                sev = Severity.MEDIUM;
            }
            Map<String, Object[]> byUser = new LinkedHashMap<>();
            List<Ref> refs = new ArrayList<>();
            LocalDateTime last = list.get(0).returnDate();
            for (ReturnFact r : list) {
                Object[] u = byUser.computeIfAbsent(r.userName(), k -> new Object[]{0, BigDecimal.ZERO, r.userRole()});
                u[0] = (int) u[0] + 1;
                u[1] = ((BigDecimal) u[1]).add(Amounts.money(r.refund()));
                refs.add(GuardianRule.sale(r.saleId(), null));
                if (r.returnDate().isAfter(last)) last = r.returnDate();
            }
            List<Map<String, Object>> users = new ArrayList<>();
            byUser.forEach((name, u) -> users.add(params("name", name, "count", u[0],
                    "amount", ((BigDecimal) u[1]).doubleValue(), "role", u[2])));
            out.add(GuardianRule.alert("G8a:day:" + e.getKey(), "G8a", "RETURNS_SPIKE", sev, "ACTIVITY",
                    params("count", list.size(), "refunds", refunds.doubleValue(), "sales", daySales.doubleValue(),
                            "percent", percent, "minAmount", GuardianThresholds.RETURNS_MIN_AMOUNT.doubleValue(),
                            "mediumPercent", GuardianThresholds.RETURNS_MEDIUM_PERCENT,
                            "highPercent", GuardianThresholds.RETURNS_HIGH_PERCENT,
                            "day", e.getKey().toString(), "byUser", users),
                    refs, null, null, last, null));
        }
        return out;
    }

    /**
     * G8b. A 7-day window may start before the chosen month: returns of the 14
     * days before it are read too ({@code productReturns}). For each product,
     * in time order, the count of returns in the 7 days ending at each return is
     * computed. An EPISODE starts at the return where that count reaches 3 (the
     * return before it was below 3) and goes on while it stays at 3 or more. An
     * episode is reported once, in the period of the return that reached the
     * limit: never again in the next month, never for an episode that reached
     * the limit before the period. One alert per product per period (the
     * episode with the most returns; "episodes" says how many there were).
     */
    private static List<Alert> perProduct(GuardianData d) {
        Map<String, List<ReturnFact>> byProduct = new LinkedHashMap<>();
        for (ReturnFact r : d.productReturns()) {
            String key = r.productId() != null ? r.productId().toString() : "name:" + r.productName();
            byProduct.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        LocalDateTime start = d.from().atStartOfDay();
        LocalDateTime end = d.to().plusDays(1).atStartOfDay();
        List<Alert> out = new ArrayList<>();
        for (var e : byProduct.entrySet()) {
            List<ReturnFact> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparing(ReturnFact::returnDate).thenComparingLong(ReturnFact::returnId));
            int[] count = new int[list.size()];
            for (int j = 0; j < list.size(); j++) count[j] = windowCount(list, j);
            int episodes = 0, bestPeak = 0, bestEnd = -1;
            for (int j = 0; j < list.size(); j++) {
                boolean trigger = count[j] >= GuardianThresholds.PRODUCT_RETURNS_REPEAT
                        && (j == 0 || count[j - 1] < GuardianThresholds.PRODUCT_RETURNS_REPEAT);
                LocalDateTime t = list.get(j).returnDate();
                if (!trigger || t.isBefore(start) || !t.isBefore(end)) continue;
                episodes++;
                // The episode's peak: the largest window while it lasts (inside the period).
                int peakAt = j;
                for (int k = j + 1; k < list.size() && count[k] >= GuardianThresholds.PRODUCT_RETURNS_REPEAT
                        && list.get(k).returnDate().isBefore(end); k++) {
                    if (count[k] > count[peakAt]) peakAt = k;
                }
                if (count[peakAt] > bestPeak) {
                    bestPeak = count[peakAt];
                    bestEnd = peakAt;
                }
            }
            if (bestEnd < 0) continue;
            ReturnFact last = list.get(bestEnd);
            LocalDateTime from = last.returnDate().minusDays(GuardianThresholds.PRODUCT_RETURNS_DAYS);
            int units = 0;
            List<Ref> refs = new ArrayList<>();
            for (int i = 0; i <= bestEnd; i++) {
                if (list.get(i).returnDate().isAfter(from)) {
                    units += list.get(i).quantity();
                    refs.add(GuardianRule.sale(list.get(i).saleId(), null));
                }
            }
            if (last.productId() != null) refs.add(GuardianRule.product(last.productId(), last.productName()));
            boolean exists = d.product(last.productId()).isPresent();
            out.add(GuardianRule.alert("G8b:product:" + e.getKey(), "G8b", "PRODUCT_RETURNS_REPEATED", Severity.MEDIUM,
                    "ACTIVITY",
                    params("productName", last.productName(), "count", bestPeak, "units", units,
                            "days", GuardianThresholds.PRODUCT_RETURNS_DAYS,
                            "minCount", GuardianThresholds.PRODUCT_RETURNS_REPEAT, "episodes", episodes),
                    refs, null, null, last.returnDate(),
                    exists ? GuardianRule.action("PRODUCT", last.productId()) : null));
        }
        return out;
    }

    /** Returns of the product in the 7 days ending at return j: (date_j − 7 days, date_j]. */
    private static int windowCount(List<ReturnFact> list, int j) {
        LocalDateTime from = list.get(j).returnDate().minusDays(GuardianThresholds.PRODUCT_RETURNS_DAYS);
        int n = 0;
        for (int i = 0; i <= j; i++) if (list.get(i).returnDate().isAfter(from)) n++;
        return n;
    }
}
