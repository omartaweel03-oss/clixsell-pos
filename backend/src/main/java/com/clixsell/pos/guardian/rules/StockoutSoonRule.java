package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.Amounts;
import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.GuardianThresholds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.clixsell.pos.guardian.rules.GuardianRule.params;

/**
 * G6 – stockout soon (Figure 3.1 "expected stockout date", "order stock before
 * it runs out"): products that, at their own sales rate of the last 14 days,
 * run out within 3 days → one grouped HIGH alert. Products running out within
 * 7 days appear in "Products most at risk" (MEDIUM). Out-of-stock products are
 * G5. The calculation is {@link StockoutForecast}; current state.
 */
public final class StockoutSoonRule implements GuardianRule {

    public static List<StockoutForecast> forecasts(GuardianData d) {
        List<StockoutForecast> out = new ArrayList<>();
        for (ProductFact p : d.products()) out.add(StockoutForecast.of(p, d));
        return out;
    }

    @Override
    public List<Alert> evaluate(GuardianData d) {
        List<StockoutForecast> soon = forecasts(d).stream().filter(StockoutForecast::soon)
                .sorted(Comparator.comparingDouble(StockoutForecast::daysLeft)).toList();
        if (soon.isEmpty()) return List.of();
        List<Map<String, Object>> items = new ArrayList<>();
        for (StockoutForecast f : soon) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", f.product().id());
            m.put("name", f.product().name());
            m.put("quantity", f.product().quantity());
            m.put("unitsPerDay", Amounts.round2(f.unitsPerDay()));
            m.put("daysLeft", Amounts.round1(f.daysLeft()));
            m.put("expectedStockout", f.expectedStockout().toString());
            items.add(m);
        }
        return List.of(GuardianRule.alert("G6:soon", "G6", "STOCKOUT_SOON", Severity.HIGH, "STOCK",
                params("count", soon.size(), "days", (int) GuardianThresholds.STOCKOUT_HIGH_DAYS,
                        "windowDays", GuardianThresholds.STOCKOUT_WINDOW_DAYS, "products", items),
                soon.stream().map(f -> GuardianRule.product(f.product().id(), f.product().name())).toList(),
                null, null, null, GuardianRule.action("STOCK_ALERTS", null)));
    }
}
