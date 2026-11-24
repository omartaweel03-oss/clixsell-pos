package com.clixsell.pos.purchase;

import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianThresholds;
import com.clixsell.pos.guardian.rules.StockoutForecast;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Purchase quantity rules (approved decision D5). Rule-based, explained with numbers; no AI,
 * no confidence score. The sales rate is Guardian Mode's own {@link StockoutForecast}
 * (last 14 days, at least 7 observed days and 3 net units; net = sold − returned).
 *
 * <pre>
 * candidates:  out of stock (quantity 0), at or below the minimum, or running out within 7 days
 * class:       OUT_OF_STOCK › URGENT (≤ 3 days, the same products as Guardian's alert) › SOON (4–7 days) › LOW_STOCK
 * SALES_RATE      enough sales data:        target = min + ⌈net × 14 ÷ observed days⌉   suggested = target − quantity
 * MIN_STOCK_ONLY  not enough data, min > 0: target = 2 × min                           suggested = target − quantity
 * MANUAL          not enough data, min = 0: no suggested quantity (the manager decides)
 * </pre>
 * A suggestion ≤ 0 is not a recommendation; a suggestion is never above 100000. A negative
 * quantity in the database is reported as INCONSISTENT and gets no suggestion.
 */
public final class ReorderCalculator {

    public static final int HORIZON_DAYS = 7;
    public static final int URGENT_DAYS = (int) GuardianThresholds.STOCKOUT_HIGH_DAYS;
    public static final int COVER_DAYS = 14;
    public static final int MAX_QUANTITY = 100_000;

    public enum Classification { OUT_OF_STOCK, URGENT, SOON, LOW_STOCK, INCONSISTENT }

    public enum Basis { SALES_RATE, MIN_STOCK_ONLY, MANUAL }

    /**
     * The calculation for one product. {@code suggestedQuantity} is null for MANUAL and
     * INCONSISTENT; unitsPerDay / daysLeft / expectedStockout are null without enough data.
     */
    public record Result(Classification classification, Basis basis, Integer suggestedQuantity, Integer targetStock,
                         int observedDays, long netUnits, Double unitsPerDay, Double daysLeft,
                         LocalDate expectedStockout, boolean enoughSalesData) {
    }

    private ReorderCalculator() {
    }

    /**
     * The calculation for {@code p}, or null when the product needs no purchase now.
     *
     * @param netUnitsLast14 product id → net units of the last 14 days (Guardian's query)
     */
    public static Result calculate(ProductFact p, Map<Long, Long> netUnitsLast14, LocalDateTime now) {
        GuardianData data = new GuardianData(now, now.toLocalDate(), now.toLocalDate(), true, 0, now, List.of(p),
                List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), netUnitsLast14, Set.of());
        if (p.quantity() < 0) {
            StockoutForecast f = StockoutForecast.of(withQuantity(p, 1), data);
            return new Result(Classification.INCONSISTENT, Basis.MANUAL, null, null, f.observedDays(), f.netUnits(),
                    null, null, null, false);
        }
        StockoutForecast f = StockoutForecast.of(p, data);
        // StockoutForecast answers OUT_OF_STOCK before looking at sales. The sales rate does not
        // depend on the quantity, so for an out-of-stock product it is asked for the same product
        // with one unit in stock — the rate is Guardian's own calculation, not a copy of it.
        StockoutForecast rate = p.quantity() <= 0 ? StockoutForecast.of(withQuantity(p, 1), data) : f;
        boolean enough = rate.ok();

        Classification c;
        if (p.quantity() <= 0) c = Classification.OUT_OF_STOCK;
        else if (f.ok() && f.daysLeft() <= URGENT_DAYS) c = Classification.URGENT;
        else if (f.ok() && f.daysLeft() <= HORIZON_DAYS) c = Classification.SOON;
        else if (p.quantity() <= p.minStock()) c = Classification.LOW_STOCK;
        else return null; // enough stock for more than 7 days, above the minimum

        Basis basis;
        Integer target;
        if (enough) {
            basis = Basis.SALES_RATE;
            // ⌈net × 14 ÷ observed⌉ in whole numbers (no floating-point rounding surprises)
            long cover = (rate.netUnits() * COVER_DAYS + rate.observedDays() - 1) / rate.observedDays();
            target = (int) Math.min((long) p.minStock() + cover, Integer.MAX_VALUE);
        } else if (p.minStock() > 0) {
            basis = Basis.MIN_STOCK_ONLY;
            target = (int) Math.min(2L * p.minStock(), Integer.MAX_VALUE);
        } else {
            basis = Basis.MANUAL;
            target = null;
        }
        Integer suggested = null;
        if (target != null) {
            long s = (long) target - p.quantity();
            if (s <= 0) return null; // the rules ask for nothing: not a recommendation
            suggested = (int) Math.min(s, MAX_QUANTITY);
        }
        return new Result(c, basis, suggested, target, rate.observedDays(), rate.netUnits(),
                enough ? rate.unitsPerDay() : null, f.ok() ? f.daysLeft() : null,
                f.ok() ? f.expectedStockout() : null, enough);
    }

    private static ProductFact withQuantity(ProductFact p, int quantity) {
        return new ProductFact(p.id(), p.name(), p.category(), p.price(), p.cost(), quantity, p.minStock(), p.createdAt());
    }
}
