package com.clixsell.pos.guardian.rules;

import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianThresholds;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Expected stockout of one product (used by G6, T1 and "Products most at risk").
 *
 * <pre>
 * observed days = the smaller of 14 and the product's age in days (today included); at least 7
 * net units    = units sold − units returned in those last 14 days; at least 3
 * units per day = net units ÷ observed days
 * days left    = quantity ÷ units per day
 * stockout date = today + days left (rounded down)
 * </pre>
 * Without enough data the result is INSUFFICIENT_DATA and no date is shown.
 *
 * @param status OK, INSUFFICIENT_DATA or OUT_OF_STOCK
 */
public record StockoutForecast(ProductFact product, String status, int observedDays, long netUnits,
                               Double unitsPerDay, Double daysLeft, LocalDate expectedStockout) {

    public static StockoutForecast of(ProductFact p, GuardianData d) {
        long net = d.netUnitsLast14().getOrDefault(p.id(), 0L);
        LocalDate today = d.today();
        long age = ChronoUnit.DAYS.between(p.createdAt().toLocalDate(), today) + 1;
        int observed = (int) Math.max(0, Math.min(GuardianThresholds.STOCKOUT_WINDOW_DAYS, age));
        if (p.outOfStock()) return new StockoutForecast(p, "OUT_OF_STOCK", observed, net, null, 0.0, null);
        if (observed < GuardianThresholds.STOCKOUT_MIN_OBSERVED_DAYS || net < GuardianThresholds.STOCKOUT_MIN_NET_UNITS) {
            return new StockoutForecast(p, "INSUFFICIENT_DATA", observed, net, null, null, null);
        }
        double rate = (double) net / observed;
        double daysLeft = p.quantity() / rate;
        return new StockoutForecast(p, "OK", observed, net, rate, daysLeft, today.plusDays((long) Math.floor(daysLeft)));
    }

    public boolean ok() {
        return "OK".equals(status);
    }

    /** OK and runs out within 3 days. */
    public boolean soon() {
        return ok() && daysLeft <= GuardianThresholds.STOCKOUT_HIGH_DAYS;
    }

    /** OK and runs out within 7 days. */
    public boolean withinWeek() {
        return ok() && daysLeft <= GuardianThresholds.STOCKOUT_MEDIUM_DAYS;
    }
}
