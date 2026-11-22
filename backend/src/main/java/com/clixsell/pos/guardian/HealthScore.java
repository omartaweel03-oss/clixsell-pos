package com.clixsell.pos.guardian;

import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Health;
import com.clixsell.pos.guardian.GuardianResponses.HealthPart;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.clixsell.pos.guardian.GuardianThresholds.HEALTH_MIN_COST_COVERAGE_PERCENT;
import static com.clixsell.pos.guardian.GuardianThresholds.HEALTH_MIN_INVOICES;
import static com.clixsell.pos.guardian.GuardianThresholds.HEALTH_MIN_PARTS;

/**
 * Store Health Score (Figure 3.1 "Store health index"), approved decision D3:
 * four parts of 25 points each, every part only when it has enough data.
 *
 * <pre>
 * STOCK     = 25 × products not low / out ÷ all products        current month only, ≥ 1 product
 * PROFIT    = 25 × (1 − confirmed-loss ÷ fully-costed invoices)  ≥ 5 fully-costed invoices AND ≥ 50% of the invoices
 * DISCOUNTS = 25 × (1 − high-discount (G1) ÷ completed invoices) ≥ 5 completed invoices
 * ACTIVITY  = 25 × (1 − days with an activity alert ÷ active days) ≥ 5 completed invoices
 *             (activity alerts = G7, G8a, G9; active day = a day with a sale or an activity alert)
 *
 * score = Σ available points ÷ (25 × available parts) × 100, only with 3 or more parts;
 *         otherwise no number ("not enough data").
 * band  = ≥ 80 GOOD, 60–79 ATTENTION, &lt; 60 RISK
 * </pre>
 * Invoices with a missing cost never count as losses (they reduce the coverage instead).
 */
public final class HealthScore {

    private HealthScore() {
    }

    public static Health of(GuardianData d, long highDiscountInvoices, List<Alert> alerts) {
        List<HealthPart> parts = List.of(stock(d), profit(d), discounts(d, highDiscountInvoices), activity(d, alerts));
        double points = 0;
        int used = 0;
        for (HealthPart p : parts) {
            if (p.available()) {
                points += p.points();
                used++;
            }
        }
        if (used < HEALTH_MIN_PARTS) return new Health(null, null, used, "INSUFFICIENT_DATA", round(parts));
        int score = (int) Math.round(points / (25.0 * used) * 100);
        return new Health(score, band(score), used, null, round(parts));
    }

    public static String band(int percent) {
        if (percent >= GuardianThresholds.HEALTH_GOOD) return "GOOD";
        if (percent >= GuardianThresholds.HEALTH_ATTENTION) return "ATTENTION";
        return "RISK";
    }

    static HealthPart stock(GuardianData d) {
        int total = d.products().size();
        int healthy = (int) d.products().stream().filter(ProductFact::healthyStock).count();
        Map<String, Object> n = numbers("healthyProducts", healthy, "products", total);
        if (!d.currentPeriod()) return new HealthPart("STOCK", false, null, "PAST_PERIOD", n);
        if (total == 0) return new HealthPart("STOCK", false, null, "NO_PRODUCTS", n);
        return new HealthPart("STOCK", true, 25.0 * healthy / total, null, n);
    }

    static HealthPart profit(GuardianData d) {
        long invoices = d.invoices().size();
        long complete = 0, losses = 0;
        for (InvoiceFact i : d.invoices()) {
            InvoiceCostStatus s = InvoiceCostStatus.of(i);
            if (s != InvoiceCostStatus.INCOMPLETE_COST) complete++;
            if (s == InvoiceCostStatus.CONFIRMED_LOSS) losses++;
        }
        Integer coverage = invoices == 0 ? null : (int) Math.floor(complete * 100.0 / invoices);
        Map<String, Object> n = numbers("invoices", invoices, "completeCostInvoices", complete,
                "confirmedLossInvoices", losses, "coveragePercent", coverage,
                "minInvoices", HEALTH_MIN_INVOICES, "minCoveragePercent", HEALTH_MIN_COST_COVERAGE_PERCENT);
        if (complete < HEALTH_MIN_INVOICES) {
            return new HealthPart("PROFIT", false, null, invoices < HEALTH_MIN_INVOICES ? "NOT_ENOUGH_INVOICES"
                    : "COST_COVERAGE_LOW", n);
        }
        if (complete * 100 < (long) HEALTH_MIN_COST_COVERAGE_PERCENT * invoices) {
            return new HealthPart("PROFIT", false, null, "COST_COVERAGE_LOW", n);
        }
        return new HealthPart("PROFIT", true, 25.0 * (1 - (double) losses / complete), null, n);
    }

    static HealthPart discounts(GuardianData d, long high) {
        long invoices = d.invoices().size();
        Map<String, Object> n = numbers("invoices", invoices, "highDiscountInvoices", high,
                "minInvoices", HEALTH_MIN_INVOICES);
        if (invoices < HEALTH_MIN_INVOICES) return new HealthPart("DISCOUNTS", false, null, "NOT_ENOUGH_INVOICES", n);
        return new HealthPart("DISCOUNTS", true, 25.0 * (1 - (double) high / invoices), null, n);
    }

    static HealthPart activity(GuardianData d, List<Alert> alerts) {
        Set<LocalDate> active = new TreeSet<>();
        for (InvoiceFact i : d.invoices()) active.add(i.completedAt().toLocalDate());
        Set<LocalDate> alertDays = new TreeSet<>();
        for (Alert a : alerts) {
            if (a.day() != null && Set.of("G7", "G8a", "G9").contains(a.rule())) alertDays.add(a.day());
        }
        active.addAll(alertDays);
        long invoices = d.invoices().size();
        boolean partial = d.trackedSince() != null && d.from().isBefore(d.trackedSince().toLocalDate());
        Map<String, Object> n = numbers("activeDays", active.size(), "daysWithAlert", alertDays.size(),
                "invoices", invoices, "minInvoices", HEALTH_MIN_INVOICES,
                "deletionsTrackedSince", partial ? d.trackedSince().toLocalDate().toString() : null);
        if (invoices < HEALTH_MIN_INVOICES) return new HealthPart("ACTIVITY", false, null, "NOT_ENOUGH_INVOICES", n);
        return new HealthPart("ACTIVITY", true, 25.0 * (1 - (double) alertDays.size() / active.size()), null, n);
    }

    private static List<HealthPart> round(List<HealthPart> parts) {
        List<HealthPart> out = new ArrayList<>();
        for (HealthPart p : parts) {
            out.add(new HealthPart(p.part(), p.available(), p.points() == null ? null : Amounts.round2(p.points()),
                    p.reason(), p.numbers()));
        }
        return out;
    }

    private static Map<String, Object> numbers(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
