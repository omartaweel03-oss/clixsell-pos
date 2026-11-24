package com.clixsell.pos.purchase;

import com.clixsell.pos.guardian.GuardianData;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.rules.StockoutForecast;
import com.clixsell.pos.purchase.ReorderCalculator.Basis;
import com.clixsell.pos.purchase.ReorderCalculator.Classification;
import com.clixsell.pos.purchase.ReorderCalculator.Result;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decision D5, examples E1–E12 of the approved plan (E8, E9 and E12 need orders and suppliers:
 * they are in PurchaseApiTest). Every expected number is worked out here by hand from the
 * formulas, not taken from the code.
 */
class ReorderCalculatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0, 0);
    private static final long ID = 7;

    /** A product created {@code ageDays} days ago (observed days = min(14, age + 1)). */
    private static ProductFact p(int quantity, int minStock, int ageDays, Double cost) {
        return new ProductFact(ID, "Labneh", "dairy", 14, cost, quantity, minStock, NOW.minusDays(ageDays));
    }

    private static Result calc(ProductFact p, long netUnits) {
        return ReorderCalculator.calculate(p, Map.of(ID, netUnits), NOW);
    }

    @Test
    void e1UrgentWithEnoughSalesUsesTheSalesRate() {
        // qty 4, min 5, 28 net units in 14 days → 2/day → 2 days left (urgent)
        // target = 5 + ⌈28 × 14 / 14⌉ = 33 → suggested 33 − 4 = 29
        Result r = calc(p(4, 5, 30, 9.5), 28);
        assertThat(r.classification()).isEqualTo(Classification.URGENT);
        assertThat(r.basis()).isEqualTo(Basis.SALES_RATE);
        assertThat(r.observedDays()).isEqualTo(14);
        assertThat(r.unitsPerDay()).isEqualTo(2.0);
        assertThat(r.daysLeft()).isEqualTo(2.0);
        assertThat(r.expectedStockout()).isEqualTo(NOW.toLocalDate().plusDays(2));
        assertThat(r.targetStock()).isEqualTo(33);
        assertThat(r.suggestedQuantity()).isEqualTo(29);
    }

    @Test
    void e2SoonIsInsideSevenDaysButNotInGuardiansThreeDayAlert() {
        // qty 10, min 3, 21 net in 14 days → 1.5/day → 6.67 days (soon)
        // target = 3 + ⌈21⌉ = 24 → suggested 14
        Result r = calc(p(10, 3, 40, null), 21);
        assertThat(r.classification()).isEqualTo(Classification.SOON);
        assertThat(r.daysLeft()).isBetween(6.66, 6.67);
        assertThat(r.targetStock()).isEqualTo(24);
        assertThat(r.suggestedQuantity()).isEqualTo(14);
    }

    @Test
    void e3NewProductWithoutEnoughHistoryUsesTheMinimumOnly() {
        // created 4 days ago → 5 observed days (< 7): not enough data
        // qty 2 ≤ min 6 → target 2 × 6 = 12 → suggested 10
        Result r = calc(p(2, 6, 4, null), 4);
        assertThat(r.enoughSalesData()).isFalse();
        assertThat(r.observedDays()).isEqualTo(5);
        assertThat(r.classification()).isEqualTo(Classification.LOW_STOCK);
        assertThat(r.basis()).isEqualTo(Basis.MIN_STOCK_ONLY);
        assertThat(r.targetStock()).isEqualTo(12);
        assertThat(r.suggestedQuantity()).isEqualTo(10);
        assertThat(r.unitsPerDay()).isNull();
        assertThat(r.daysLeft()).isNull();
    }

    @Test
    void e4OutOfStockWithoutSalesAndWithoutMinimumHasNoInventedQuantity() {
        Result r = calc(p(0, 0, 40, null), 0);
        assertThat(r.classification()).isEqualTo(Classification.OUT_OF_STOCK);
        assertThat(r.basis()).isEqualTo(Basis.MANUAL);
        assertThat(r.suggestedQuantity()).isNull();
        assertThat(r.targetStock()).isNull();
    }

    @Test
    void e5ReturnsReduceTheNetSalesBelowTheMinimumData() {
        // sold 10, returned 8 → net 2 (< 3): not enough; qty 3 ≤ min 4 → target 8 → 5
        Result r = calc(p(3, 4, 40, null), 10 - 8);
        assertThat(r.netUnits()).isEqualTo(2);
        assertThat(r.basis()).isEqualTo(Basis.MIN_STOCK_ONLY);
        assertThat(r.suggestedQuantity()).isEqualTo(5);
    }

    @Test
    void e6MoreReturnsThanSalesIsNotEnoughData() {
        Result r = calc(p(3, 4, 40, null), -2);
        assertThat(r.netUnits()).isEqualTo(-2);
        assertThat(r.enoughSalesData()).isFalse();
        assertThat(r.basis()).isEqualTo(Basis.MIN_STOCK_ONLY);
        assertThat(r.suggestedQuantity()).isEqualTo(5);
    }

    @Test
    void e7EnoughStockForMoreThanSevenDaysIsNotARecommendation() {
        assertThat(calc(p(50, 5, 40, null), 14)).isNull(); // 1/day → 50 days
        assertThat(calc(p(6, 5, 40, null), 0)).isNull();   // above the minimum, no sales
    }

    @Test
    void e10ZeroMinimumWithSalesUsesTheRateOnly() {
        // qty 1, min 0, 7 net in 14 days → 0.5/day → 2 days (urgent); target 0 + ⌈7⌉ = 7 → 6
        Result r = calc(p(1, 0, 40, null), 7);
        assertThat(r.classification()).isEqualTo(Classification.URGENT);
        assertThat(r.suggestedQuantity()).isEqualTo(6);
    }

    @Test
    void e11InconsistentStockGetsNoSuggestion() {
        Result r = calc(p(-1, 5, 40, null), 28);
        assertThat(r.classification()).isEqualTo(Classification.INCONSISTENT);
        assertThat(r.suggestedQuantity()).isNull();
    }

    @Test
    void outOfStockWithHistoryStillUsesTheSalesRate() {
        // StockoutForecast says OUT_OF_STOCK before looking at sales; the rate is still Guardian's:
        // qty 0, min 5, 28 net / 14 days → target 5 + 28 = 33 → suggested 33
        Result r = calc(p(0, 5, 40, null), 28);
        assertThat(r.classification()).isEqualTo(Classification.OUT_OF_STOCK);
        assertThat(r.basis()).isEqualTo(Basis.SALES_RATE);
        assertThat(r.unitsPerDay()).isEqualTo(2.0);
        assertThat(r.suggestedQuantity()).isEqualTo(33);
        assertThat(r.daysLeft()).isNull(); // no "days left" for a product that is already out
    }

    @Test
    void roundingUpIsExactInWholeNumbers() {
        // created 8 days ago → 9 observed days; 5 net → 5 × 14 / 9 = 7.78 → 8; qty 2 (urgent: 3.6 days? no)
        // rate 0.556/day, 2 / 0.556 = 3.6 days → SOON; target = min 2 + 8 = 10 → suggested 8
        Result r = calc(p(2, 2, 8, null), 5);
        assertThat(r.observedDays()).isEqualTo(9);
        assertThat(r.classification()).isEqualTo(Classification.SOON);
        assertThat(r.targetStock()).isEqualTo(10);
        assertThat(r.suggestedQuantity()).isEqualTo(8);
        // exact multiple: 10 net in 7 observed days → 10 × 14 / 7 = 20 exactly (not 21)
        Result exact = calc(p(1, 0, 6, null), 10);
        assertThat(exact.observedDays()).isEqualTo(7);
        assertThat(exact.targetStock()).isEqualTo(20);
    }

    @Test
    void suggestionsAreNeverAboveTheMaximum() {
        Result r = calc(p(0, 0, 40, null), 1_000_000);
        assertThat(r.targetStock()).isEqualTo(1_000_000);
        assertThat(r.suggestedQuantity()).isEqualTo(ReorderCalculator.MAX_QUANTITY);
    }

    @Test
    void urgentIsExactlyGuardiansThreeDayAlertForEveryCase() {
        // Guardian's own rule: StockoutForecast.soon(). Purchasing must flag the same products as URGENT.
        int[][] cases = {{4, 5, 28}, {10, 3, 21}, {1, 0, 7}, {3, 0, 14}, {6, 0, 28}, {7, 0, 28}, {2, 2, 5}, {9, 1, 42}};
        for (int[] c : cases) {
            ProductFact fact = p(c[0], c[1], 40, null);
            Map<Long, Long> net = Map.of(ID, (long) c[2]);
            GuardianData d = new GuardianData(NOW, NOW.toLocalDate(), NOW.toLocalDate(), true, 0, NOW, List.of(fact),
                    List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), net, Set.of());
            boolean guardianUrgent = StockoutForecast.of(fact, d).soon();
            Result r = ReorderCalculator.calculate(fact, net, NOW);
            boolean purchasingUrgent = r != null && r.classification() == Classification.URGENT;
            assertThat(purchasingUrgent).as("qty %d min %d net %d", c[0], c[1], c[2]).isEqualTo(guardianUrgent);
        }
    }
}
