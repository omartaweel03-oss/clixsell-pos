package com.clixsell.pos.guardian;

import com.clixsell.pos.guardian.GuardianData.CancelFact;
import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.LogFact;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianData.ReturnFact;
import com.clixsell.pos.guardian.GuardianResponses.ActivitySummary;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.AtRiskProduct;
import com.clixsell.pos.guardian.GuardianResponses.CostData;
import com.clixsell.pos.guardian.GuardianResponses.Health;
import com.clixsell.pos.guardian.GuardianResponses.MapTile;
import com.clixsell.pos.guardian.GuardianResponses.ProfitProtection;
import com.clixsell.pos.guardian.GuardianResponses.Recommendation;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.rules.BelowCostRule;
import com.clixsell.pos.guardian.rules.CanceledInvoicesRule;
import com.clixsell.pos.guardian.rules.GuardianRule;
import com.clixsell.pos.guardian.rules.HighDiscountRule;
import com.clixsell.pos.guardian.rules.MaxDiscountRiskRule;
import com.clixsell.pos.guardian.rules.PriceBelowCostRule;
import com.clixsell.pos.guardian.rules.ProductDeletionsRule;
import com.clixsell.pos.guardian.rules.ReturnsRule;
import com.clixsell.pos.guardian.rules.StockRule;
import com.clixsell.pos.guardian.rules.StockoutForecast;
import com.clixsell.pos.guardian.rules.StockoutSoonRule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs every Guardian rule on one store's data and builds the dashboard
 * sections of Figure 3.1. Pure calculation: no database, no randomness.
 */
public final class GuardianAnalysis {

    /** G1 … G9, in this order. */
    public static final List<GuardianRule> RULES = List.of(new HighDiscountRule(), new BelowCostRule(),
            new PriceBelowCostRule(), new MaxDiscountRiskRule(), new StockRule(), new StockoutSoonRule(),
            new CanceledInvoicesRule(), new ReturnsRule(), new ProductDeletionsRule());

    /** Alerts about what a cashier does at the POS (the "cashier area" tile). */
    static final Set<String> CASHIER_RULES = Set.of("G1", "G7", "G8a");
    static final Set<String> PROFIT_RULES = Set.of("G2a", "G2b", "G3");

    public record Result(List<Alert> alerts, int criticalCount, int profitRiskCount, CostData costData,
                         Health health, List<Recommendation> recommendations, List<MapTile> storeMap,
                         List<AtRiskProduct> atRisk, List<ProfitProtection> profitProtection,
                         ActivitySummary activity) {
    }

    private GuardianAnalysis() {
    }

    public static List<Alert> alerts(GuardianData d) {
        List<Alert> all = new ArrayList<>();
        for (GuardianRule r : RULES) all.addAll(r.evaluate(d));
        LocalDateTime now = d.now();
        all.sort(Comparator.comparingInt((Alert a) -> -a.severity().weight())
                .thenComparing((Alert a) -> a.occurredAt() == null ? now : a.occurredAt(), Comparator.reverseOrder())
                .thenComparing(Alert::key));
        return all;
    }

    public static Result analyze(GuardianData d) {
        List<Alert> alerts = alerts(d);
        List<HighDiscountRule.Evaluation> highDiscounts = HighDiscountRule.all(d);
        List<StockoutForecast> forecasts = StockoutSoonRule.forecasts(d);
        int critical = (int) alerts.stream().filter(a -> a.severity() == Severity.HIGH).count();
        int profitRisks = (int) alerts.stream().filter(a -> PROFIT_RULES.contains(a.rule())).count();
        return new Result(alerts, critical, profitRisks, costData(d),
                HealthScore.of(d, highDiscounts.size(), alerts),
                Recommendations.of(d, forecasts), storeMap(d, alerts), atRisk(forecasts),
                profitProtection(d), activity(d, alerts, highDiscounts.size()));
    }

    static CostData costData(GuardianData d) {
        long complete = 0, incomplete = 0, losses = 0;
        for (InvoiceFact i : d.invoices()) {
            switch (InvoiceCostStatus.of(i)) {
                case INCOMPLETE_COST -> incomplete++;
                case CONFIRMED_LOSS -> {
                    complete++;
                    losses++;
                }
                case NO_LOSS -> complete++;
            }
        }
        long withoutCost = d.products().stream().filter(p -> p.cost() == null).count();
        return new CostData(d.invoices().size(), complete, incomplete, losses, withoutCost);
    }

    /** One tile per category that has products (stock now), plus the cashier area (alerts of the period). */
    static List<MapTile> storeMap(GuardianData d, List<Alert> alerts) {
        Map<String, int[]> cats = new TreeMap<>();
        for (ProductFact p : d.products()) {
            int[] c = cats.computeIfAbsent(p.category(), k -> new int[2]);
            c[0]++;
            if (p.healthyStock()) c[1]++;
        }
        List<MapTile> tiles = new ArrayList<>();
        cats.forEach((cat, c) -> {
            int percent = (int) Math.round(c[1] * 100.0 / c[0]);
            tiles.add(new MapTile("CATEGORY", cat, percent, HealthScore.band(percent), c[0], c[1], 0, 0));
        });
        int high = 0, medium = 0;
        for (Alert a : alerts) {
            if (!CASHIER_RULES.contains(a.rule())) continue;
            if (a.severity() == Severity.HIGH) high++;
            else if (a.severity() == Severity.MEDIUM) medium++;
        }
        String band = high > 0 ? "RISK" : medium > 0 ? "ATTENTION" : "GOOD";
        tiles.add(new MapTile("CASHIER_AREA", null, null, band, 0, 0, high, medium));
        return tiles;
    }

    /** Out of stock, low stock and products running out within 7 days; most urgent first. */
    static List<AtRiskProduct> atRisk(List<StockoutForecast> forecasts) {
        List<AtRiskProduct> out = new ArrayList<>();
        for (StockoutForecast f : forecasts) {
            ProductFact p = f.product();
            if (!p.outOfStock() && !p.lowStock() && !f.withinWeek()) continue;
            Severity risk;
            if (p.outOfStock() || f.soon()) risk = Severity.HIGH;
            else if (f.withinWeek()) risk = Severity.MEDIUM;
            else risk = Severity.LOW;
            out.add(new AtRiskProduct(p.id(), p.name(), p.quantity(), p.minStock(), f.expectedStockout(),
                    f.daysLeft() == null ? null : Amounts.round1(f.daysLeft()),
                    f.unitsPerDay() == null ? null : Amounts.round2(f.unitsPerDay()), f.status(), risk));
        }
        out.sort(Comparator.comparingInt((AtRiskProduct a) -> -a.risk().weight())
                .thenComparing(a -> a.daysLeft() == null ? Double.MAX_VALUE : a.daysLeft())
                .thenComparing(AtRiskProduct::name));
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    /** G3 products first (price ≤ cost), then G4 (the maximum discount would sell below cost). */
    static List<ProfitProtection> profitProtection(GuardianData d) {
        List<ProfitProtection> below = new ArrayList<>();
        List<ProfitProtection> risk = new ArrayList<>();
        for (ProductFact p : d.products()) {
            if (p.cost() == null) continue;
            int cmp = Amounts.money(p.price()).compareTo(Amounts.money(p.cost()));
            if (cmp <= 0) {
                double after = Amounts.round2(p.price() * (1 - d.maxDiscountPercent() / 100));
                below.add(new ProfitProtection(p.id(), p.name(), p.cost(), p.price(), d.maxDiscountPercent(), after, 0,
                        cmp < 0 ? "PRICE_BELOW_COST" : "PRICE_EQUALS_COST"));
                continue;
            }
            MaxDiscountRiskRule.Risk r = MaxDiscountRiskRule.check(p, d.maxDiscountPercent());
            if (r != null) {
                risk.add(new ProfitProtection(p.id(), p.name(), p.cost(), p.price(), r.maxPercent(), r.priceAfterMax(),
                        r.safePercent(), "MAX_DISCOUNT_RISK"));
            }
        }
        below.sort(Comparator.comparingDouble(x -> x.price() - x.cost()));
        risk.sort(Comparator.comparingDouble(ProfitProtection::safeDiscountPercent));
        below.addAll(risk);
        return below;
    }

    /**
     * Cases of the period that belong to an alert: every high-discount invoice
     * (G1 condition), cancellations on G7 days, returns on G8a days, deletions in
     * G9 groups.
     */
    static ActivitySummary activity(GuardianData d, List<Alert> alerts, long highDiscounts) {
        Set<LocalDate> cancelDays = new HashSet<>();
        Set<LocalDate> returnDays = new HashSet<>();
        Set<String> deletionGroups = new HashSet<>();
        for (Alert a : alerts) {
            switch (a.rule()) {
                case "G7" -> cancelDays.add(a.day());
                case "G8a" -> returnDays.add(a.day());
                case "G9" -> deletionGroups.add(a.key());
                default -> { }
            }
        }
        long cancels = 0, returns = 0, deletions = 0;
        for (CancelFact c : d.cancels()) if (cancelDays.contains(c.canceledAt().toLocalDate())) cancels++;
        for (ReturnFact r : d.returns()) if (returnDays.contains(r.returnDate().toLocalDate())) returns++;
        for (LogFact l : d.deletions()) {
            if (deletionGroups.contains("G9:user:" + l.userId() + ":" + l.createdAt().toLocalDate())) deletions++;
        }
        long total = highDiscounts + cancels + returns + deletions;
        int days = (int) ChronoUnit.DAYS.between(d.from(), d.to()) + 1;
        return new ActivitySummary(highDiscounts, cancels, returns, deletions, total, days,
                Amounts.round1((double) total / days), d.trackedSince());
    }
}
