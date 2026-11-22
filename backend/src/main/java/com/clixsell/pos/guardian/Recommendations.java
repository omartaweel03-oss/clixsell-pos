package com.clixsell.pos.guardian;

import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianResponses.Action;
import com.clixsell.pos.guardian.GuardianResponses.Recommendation;
import com.clixsell.pos.guardian.rules.StockoutForecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based recommendations (Figure 3.1 "recommendations"). They are
 * suggestions with their reason and numbers: nothing is changed or ordered
 * automatically, and there is no "AI" and no confidence score.
 *
 * <ul>
 *   <li><b>T1 REORDER_SOON</b> – products that run out within 3 days at their own
 *       sales rate (G6). Opens Stock Alerts; creating a purchase order is not
 *       available yet (Purchase Orders are a later phase).</li>
 *   <li><b>T2 LOW_MARGIN</b> – a recorded cost and a margin (price − cost) ÷ price
 *       above 0 and below 10%. No new price is suggested.</li>
 *   <li><b>T3 SLOW_MOVING</b> – more than the minimum stock, product at least 30 days
 *       old and not one unit sold in the last 30 days (instead of "near expiry":
 *       expiry dates are not stored).</li>
 * </ul>
 */
public final class Recommendations {

    private Recommendations() {
    }

    public static List<Recommendation> of(GuardianData d, List<StockoutForecast> forecasts) {
        return List.of(reorderSoon(forecasts), lowMargin(d), slowMoving(d));
    }

    static Recommendation reorderSoon(List<StockoutForecast> forecasts) {
        List<Map<String, Object>> items = new ArrayList<>();
        forecasts.stream().filter(StockoutForecast::soon).sorted(Comparator.comparingDouble(StockoutForecast::daysLeft))
                .forEach(f -> {
                    Map<String, Object> m = item(f.product());
                    m.put("quantity", f.product().quantity());
                    m.put("daysLeft", Amounts.round1(f.daysLeft()));
                    m.put("expectedStockout", f.expectedStockout().toString());
                    items.add(m);
                });
        return new Recommendation("T1", "REORDER_SOON", items.size(), items, new Action("STOCK_ALERTS", null));
    }

    static Recommendation lowMargin(GuardianData d) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductFact p : d.products()) {
            if (p.cost() == null) continue;
            BigDecimal price = Amounts.money(p.price());
            BigDecimal cost = Amounts.money(p.cost());
            if (price.compareTo(cost) <= 0) continue; // G3 covers price ≤ cost
            BigDecimal margin = price.subtract(cost).multiply(BigDecimal.valueOf(100)).divide(price, 4, RoundingMode.HALF_UP);
            if (margin.compareTo(BigDecimal.valueOf(GuardianThresholds.LOW_MARGIN_PERCENT)) >= 0) continue;
            Map<String, Object> m = item(p);
            m.put("price", p.price());
            m.put("cost", p.cost());
            m.put("marginPercent", margin.setScale(2, RoundingMode.HALF_UP).doubleValue());
            items.add(m);
        }
        items.sort(Comparator.comparingDouble(m -> (double) m.get("marginPercent")));
        return new Recommendation("T2", "LOW_MARGIN", items.size(), items, new Action("PRODUCT", null));
    }

    static Recommendation slowMoving(GuardianData d) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProductFact p : d.products()) {
            long age = ChronoUnit.DAYS.between(p.createdAt().toLocalDate(), d.today());
            if (p.quantity() <= p.minStock() || age < GuardianThresholds.SLOW_MOVING_DAYS) continue;
            if (d.soldLast30().contains(p.id())) continue;
            Map<String, Object> m = item(p);
            m.put("quantity", p.quantity());
            m.put("minStock", p.minStock());
            m.put("ageDays", age);
            items.add(m);
        }
        return new Recommendation("T3", "SLOW_MOVING", items.size(), items, new Action("PRODUCT", null));
    }

    private static Map<String, Object> item(ProductFact p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", p.id());
        m.put("name", p.name());
        return m;
    }
}
