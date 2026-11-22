package com.clixsell.pos.guardian;

import com.clixsell.pos.guardian.GuardianData.CancelFact;
import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.LineFact;
import com.clixsell.pos.guardian.GuardianData.LogFact;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianData.ReturnFact;
import com.clixsell.pos.guardian.GuardianResponses.Alert;
import com.clixsell.pos.guardian.GuardianResponses.AtRiskProduct;
import com.clixsell.pos.guardian.GuardianResponses.Health;
import com.clixsell.pos.guardian.GuardianResponses.MapTile;
import com.clixsell.pos.guardian.GuardianResponses.Recommendation;
import com.clixsell.pos.guardian.GuardianResponses.Severity;
import com.clixsell.pos.guardian.rules.BelowCostRule;
import com.clixsell.pos.guardian.rules.CanceledInvoicesRule;
import com.clixsell.pos.guardian.rules.HighDiscountRule;
import com.clixsell.pos.guardian.rules.MaxDiscountRiskRule;
import com.clixsell.pos.guardian.rules.PriceBelowCostRule;
import com.clixsell.pos.guardian.rules.ProductDeletionsRule;
import com.clixsell.pos.guardian.rules.ReturnsRule;
import com.clixsell.pos.guardian.rules.StockRule;
import com.clixsell.pos.guardian.rules.StockoutSoonRule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every Guardian rule (G1 … G9), recommendation (T1 … T3), the invoice cost
 * classification and the Store Health Score, with exact numbers (final plan,
 * part 4 and part 5). Pure calculations: no database.
 */
class GuardianRulesTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 15, 0);
    static final LocalDate TODAY = NOW.toLocalDate();
    static final long AHMAD = 1, SARA = 2, ADMIN = 3;

    /** Builds the data of one store for the rules. */
    static class Data {
        LocalDate from = TODAY.withDayOfMonth(1);
        LocalDate to = TODAY;
        boolean current = true;
        double max = 10;
        LocalDateTime tracked = LocalDateTime.of(2026, 11, 1, 0, 0);
        final List<ProductFact> products = new ArrayList<>();
        final List<InvoiceFact> invoices = new ArrayList<>();
        final List<LineFact> lines = new ArrayList<>();
        final List<CancelFact> cancels = new ArrayList<>();
        final List<ReturnFact> returns = new ArrayList<>();
        final Map<Long, LogFact> discountLogs = new HashMap<>();
        final Map<Long, LogFact> cancelLogs = new HashMap<>();
        final List<LogFact> deletions = new ArrayList<>();
        final Map<Long, Long> net14 = new HashMap<>();
        final Set<Long> sold30 = new HashSet<>();
        long nextId = 1000;

        GuardianData build() {
            return new GuardianData(NOW, from, to, current, max, tracked, products, invoices, lines, cancels,
                    returns, discountLogs, cancelLogs, deletions, net14, sold30);
        }

        ProductFact product(long id, String name, String cat, double price, Double cost, int qty, int min, int ageDays) {
            ProductFact p = new ProductFact(id, name, cat, price, cost, qty, min, NOW.minusDays(ageDays));
            products.add(p);
            return p;
        }

        /** A completed invoice started by Ahmad. */
        InvoiceFact invoice(long id, double subtotal, double discount, LocalDateTime at, int lines, int withoutCost,
                            double cost) {
            double tax = Math.round((subtotal - discount) * 16) / 100.0;
            InvoiceFact i = new InvoiceFact(id, "INV-%06d".formatted(id), AHMAD, "Ahmad", subtotal, discount,
                    subtotal - discount + tax, at, lines, withoutCost, cost);
            invoices.add(i);
            return i;
        }

        /** A completed invoice paid {@code total} (no discount, cost unknown) – for the returns rule. */
        InvoiceFact sale(long id, double total, LocalDateTime at) {
            InvoiceFact i = new InvoiceFact(id, "INV-%06d".formatted(id), AHMAD, "Ahmad", total, 0, total, at, 1, 1, 0);
            invoices.add(i);
            return i;
        }

        LogFact discountLog(long saleId, long userId, String name, Double maxAtSale) {
            Map<String, Object> d = new HashMap<>();
            if (maxAtSale != null) d.put("maxPercentAtSale", maxAtSale);
            LogFact l = new LogFact(nextId++, "DISCOUNT_APPLIED", userId, name, saleId, "INV", d, NOW);
            discountLogs.put(saleId, l);
            return l;
        }

        CancelFact cancel(long saleId, LocalDateTime at, Long loggedUser, String name) {
            CancelFact c = new CancelFact(saleId, "INV-%06d".formatted(saleId), 20, AHMAD, "Ahmad", at);
            cancels.add(c);
            if (loggedUser != null) {
                cancelLogs.put(saleId, new LogFact(nextId++, "INVOICE_CANCELED", loggedUser, name, saleId, "INV",
                        Map.of(), at));
            }
            return c;
        }

        ReturnFact returned(long productId, String name, double refund, LocalDateTime at, long userId, String user) {
            ReturnFact r = new ReturnFact(nextId++, nextId++, productId, name, 1, refund, at, userId, user);
            returns.add(r);
            return r;
        }

        void deletion(long userId, String user, String product, LocalDateTime at) {
            deletions.add(new LogFact(nextId++, "PRODUCT_DELETED", userId, user, nextId++, product, Map.of(), at));
        }
    }

    static List<Alert> only(List<Alert> alerts, String rule) {
        return alerts.stream().filter(a -> a.rule().equals(rule)).toList();
    }

    static LocalDateTime at(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0);
    }

    // ── Invoice cost classification (final plan, part 2) ────────────────

    @Nested
    class CostClassification {

        @Test
        void confirmedLossOnlyWhenEveryLineHasACost() {
            Data d = new Data();
            // subtotal 100, discount 10 → net 90; cost 96 → loss 6. Tax is ignored.
            InvoiceFact full = d.invoice(1, 100, 10, at(20, 10), 2, 0, 96);
            InvoiceFact partial = d.invoice(2, 100, 10, at(20, 11), 2, 1, 96);
            assertThat(InvoiceCostStatus.of(full)).isEqualTo(InvoiceCostStatus.CONFIRMED_LOSS);
            // The known cost alone (96) is above the revenue (90), but one line has no cost.
            assertThat(InvoiceCostStatus.of(partial)).isEqualTo(InvoiceCostStatus.INCOMPLETE_COST);
        }

        @Test
        void roundingToTheAgoraDecidesTheBoundary() {
            Data d = new Data();
            assertThat(InvoiceCostStatus.of(d.invoice(1, 100, 10, at(20, 10), 1, 0, 89.99)))
                    .isEqualTo(InvoiceCostStatus.NO_LOSS); // profit of 0.01
            assertThat(InvoiceCostStatus.of(d.invoice(2, 100, 10, at(20, 10), 1, 0, 90.004)))
                    .isEqualTo(InvoiceCostStatus.NO_LOSS); // 0.004 rounds to 0.00
            assertThat(InvoiceCostStatus.of(d.invoice(3, 100, 10, at(20, 10), 1, 0, 90.01)))
                    .isEqualTo(InvoiceCostStatus.CONFIRMED_LOSS); // 0.01
            assertThat(InvoiceCostStatus.of(d.invoice(4, 100, 0, at(20, 10), 1, 0, 100)))
                    .isEqualTo(InvoiceCostStatus.NO_LOSS); // exactly at cost
        }
    }

    // ── G1 high discount ────────────────────────────────────────────────

    @Nested
    class G1HighDiscount {

        @Test
        void sevenAndAHalfPercentOfATenPercentMaximumIsTheLimit() {
            Data d = new Data();
            d.invoice(1, 100, 7.49, at(20, 10), 1, 1, 0);
            d.invoice(2, 100, 7.50, at(20, 11), 1, 1, 0);
            List<Alert> a = new HighDiscountRule().evaluate(d.build());
            assertThat(a).hasSize(1);
            Alert alert = a.get(0);
            assertThat(alert.key()).isEqualTo("G1:sale:2");
            assertThat(alert.code()).isEqualTo("HIGH_DISCOUNT");
            assertThat(alert.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(alert.params()).containsEntry("percent", 7.5).containsEntry("thresholdPercent", 7.5)
                    .containsEntry("maxPercent", 10.0).containsEntry("maxSource", "CURRENT");
            assertThat(alert.action().type()).isEqualTo("INVOICE");
            assertThat(alert.action().id()).isEqualTo(2L);
        }

        @Test
        void smallDiscountsUnderFiveShekelsAndStoresWithoutDiscountsAreIgnored() {
            Data d = new Data();
            d.invoice(1, 50, 4.99, at(20, 10), 1, 1, 0); // 9.98% but only 4.99 ₪
            assertThat(new HighDiscountRule().evaluate(d.build())).isEmpty();
            d.invoice(2, 100, 10, at(20, 11), 1, 1, 0);
            d.max = 0;
            assertThat(new HighDiscountRule().evaluate(d.build())).isEmpty();
        }

        @Test
        void theUserIsTheOneWhoCompletedTheSaleFromTheLogNeverTheCashierWhoStartedIt() {
            Data d = new Data();
            d.invoice(1, 100, 9, at(20, 10), 1, 1, 0);
            d.discountLog(1, SARA, "Sara", 10.0); // started by Ahmad, completed by Sara
            d.invoice(2, 100, 9, at(20, 11), 1, 1, 0); // before the log: no row
            List<Alert> a = new HighDiscountRule().evaluate(d.build());
            Alert logged = a.stream().filter(x -> x.key().equals("G1:sale:1")).findFirst().orElseThrow();
            assertThat(logged.actor().userId()).isEqualTo(SARA);
            assertThat(logged.actor().name()).isEqualTo("Sara");
            assertThat(logged.actor().source()).isEqualTo("ACTIVITY_LOG");
            assertThat(logged.params()).containsEntry("startedBy", "Ahmad").containsEntry("maxSource", "AT_SALE");
            Alert old = a.stream().filter(x -> x.key().equals("G1:sale:2")).findFirst().orElseThrow();
            assertThat(old.actor()).isNull();
            assertThat(old.actorNote()).isEqualTo("NOT_RECORDED");
            assertThat(old.params().get("startedBy")).isNull(); // no name at all without a log row
        }

        @Test
        void theMaximumSavedAtSaleTimeIsUsedWhenTheLogHasIt() {
            Data d = new Data();
            d.invoice(1, 100, 9, at(20, 10), 1, 1, 0);
            d.discountLog(1, SARA, "Sara", 20.0); // the maximum was 20% then: 9% < 15%
            assertThat(new HighDiscountRule().evaluate(d.build())).isEmpty();
        }

        @Test
        void threeByTheSameLoggedUserInOneDayGiveOneHighAlert() {
            Data d = new Data();
            for (long id = 1; id <= 3; id++) {
                d.invoice(id, 100, 8, at(20, 9 + (int) id), 1, 1, 0);
                d.discountLog(id, SARA, "Sara", 10.0);
            }
            d.invoice(4, 100, 8, at(21, 10), 1, 1, 0); // next day
            d.discountLog(4, SARA, "Sara", 10.0);
            List<Alert> a = new HighDiscountRule().evaluate(d.build());
            assertThat(a).extracting(Alert::key).containsExactlyInAnyOrder("G1:user:2:2026-11-20", "G1:sale:4");
            Alert group = a.stream().filter(x -> x.code().equals("HIGH_DISCOUNT_REPEATED")).findFirst().orElseThrow();
            assertThat(group.severity()).isEqualTo(Severity.HIGH);
            assertThat(group.params()).containsEntry("count", 3).containsEntry("totalDiscount", 24.0);
            assertThat(group.evidence()).hasSize(3);
            assertThat(group.actor().name()).isEqualTo("Sara");
        }

        @Test
        void withoutALoggedUserNothingIsGroupedByPerson() {
            Data d = new Data();
            for (long id = 1; id <= 3; id++) d.invoice(id, 100, 8, at(20, 9 + (int) id), 1, 1, 0);
            List<Alert> a = new HighDiscountRule().evaluate(d.build());
            assertThat(a).hasSize(3).allMatch(x -> x.severity() == Severity.MEDIUM && x.actor() == null);
        }

        @Test
        void aConfirmedLossGetsOnlyTheProfitAlert() {
            Data d = new Data();
            d.invoice(1, 100, 10, at(20, 10), 1, 0, 95); // high discount AND loss
            assertThat(new HighDiscountRule().evaluate(d.build())).isEmpty();
            assertThat(new BelowCostRule().evaluate(d.build())).extracting(Alert::code).containsExactly("CONFIRMED_LOSS");
        }
    }

    // ── G2 below cost ───────────────────────────────────────────────────

    @Nested
    class G2BelowCost {

        @Test
        void confirmedLossHasItsNumbersAndTheDiscountUser() {
            Data d = new Data();
            d.invoice(1, 100, 10, at(20, 10), 2, 0, 96);
            d.discountLog(1, SARA, "Sara", 10.0);
            Alert a = new BelowCostRule().evaluate(d.build()).get(0);
            assertThat(a.rule()).isEqualTo("G2b");
            assertThat(a.severity()).isEqualTo(Severity.HIGH);
            assertThat(a.category()).isEqualTo("PROFIT");
            assertThat(a.params()).containsEntry("netRevenue", 90.0).containsEntry("cost", 96.0)
                    .containsEntry("loss", 6.0).containsEntry("discount", 10.0);
            assertThat(a.actor().name()).isEqualTo("Sara");
        }

        @Test
        void anInvoiceWithAMissingCostIsNeverALoss() {
            Data d = new Data();
            d.invoice(1, 100, 10, at(20, 10), 2, 1, 96);
            GuardianData g = d.build();
            assertThat(new BelowCostRule().evaluate(g)).isEmpty();
            GuardianAnalysis.Result r = GuardianAnalysis.analyze(g);
            assertThat(r.profitRiskCount()).isZero();
            assertThat(r.costData().incompleteCostInvoices()).isEqualTo(1);
            assertThat(r.costData().confirmedLossInvoices()).isZero();
        }

        @Test
        void lineBelowItsOwnCostIsGroupedPerProductAndDay() {
            Data d = new Data();
            d.product(7, "Milk", "dairy", 2, 3.2, 20, 5, 60);
            d.lines.add(new LineFact(1, "INV-1", 7L, "Milk", 2, 2.0, 3.2, at(20, 10)));
            d.lines.add(new LineFact(2, "INV-2", 7L, "Milk", 1, 2.0, 3.2, at(20, 12)));
            d.lines.add(new LineFact(3, "INV-3", 7L, "Milk", 1, 2.0, 3.2, at(21, 9)));
            List<Alert> a = new BelowCostRule().evaluate(d.build());
            assertThat(a).hasSize(2).allMatch(x -> x.rule().equals("G2a") && x.severity() == Severity.HIGH);
            Alert first = a.stream().filter(x -> x.day().equals(LocalDate.of(2026, 11, 20))).findFirst().orElseThrow();
            assertThat(first.params()).containsEntry("times", 2).containsEntry("units", 3)
                    .containsEntry("unitPrice", 2.0).containsEntry("unitCost", 3.2)
                    .containsEntry("lossBeforeDiscount", 3.6);
            assertThat(first.action().id()).isEqualTo(7L);
        }
    }

    // ── G3 / G4 prices ──────────────────────────────────────────────────

    @Test
    void g3PriceBelowOrEqualToTheRecordedCost() {
        Data d = new Data();
        d.product(1, "Below", "dairy", 9, 10.0, 5, 1, 60);
        d.product(2, "Equal", "dairy", 10, 10.0, 5, 1, 60);
        d.product(3, "Above", "dairy", 12, 10.0, 5, 1, 60);
        d.product(4, "NoCost", "dairy", 1, null, 5, 1, 60);
        List<Alert> a = new PriceBelowCostRule().evaluate(d.build());
        assertThat(a).extracting(Alert::code, Alert::severity).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("PRICE_BELOW_COST", Severity.HIGH),
                org.assertj.core.groups.Tuple.tuple("PRICE_EQUALS_COST", Severity.MEDIUM));
    }

    @Test
    void g4TheMaximumDiscountWouldSellBelowCost() {
        Data d = new Data();
        d.product(1, "Risky", "dairy", 10, 9.5, 5, 1, 60);  // 10 × 0.9 = 9.00 < 9.50
        d.product(2, "Edge", "dairy", 10, 9.0, 5, 1, 60);   // 9.00 is not below 9.00
        d.product(3, "NoCost", "dairy", 10, null, 5, 1, 60);
        List<Alert> a = new MaxDiscountRiskRule().evaluate(d.build());
        assertThat(a).hasSize(1);
        assertThat(a.get(0).severity()).isEqualTo(Severity.LOW);
        assertThat(a.get(0).params()).containsEntry("priceAfterMax", 9.0).containsEntry("safePercent", 5.0);
        d.max = 0;
        assertThat(new MaxDiscountRiskRule().evaluate(d.build())).isEmpty();
    }

    // ── G5 / G6 stock ───────────────────────────────────────────────────

    @Test
    void g5UsesTheExistingLowAndOutOfStockRules() {
        Data d = new Data();
        d.product(1, "Out", "dairy", 5, null, 0, 5, 60);
        d.product(2, "OutNoMin", "dairy", 5, null, 0, 0, 60);
        d.product(3, "Low", "dairy", 5, null, 5, 5, 60);
        d.product(4, "Ok", "dairy", 5, null, 6, 5, 60);
        List<Alert> a = new StockRule().evaluate(d.build());
        assertThat(a).extracting(Alert::code).containsExactly("OUT_OF_STOCK", "LOW_STOCK");
        assertThat(a.get(0).params()).containsEntry("count", 2);
        assertThat(a.get(0).severity()).isEqualTo(Severity.HIGH);
        assertThat(a.get(1).params()).containsEntry("count", 1);
        assertThat(a.get(1).severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void g6StockoutFromTheProductsOwnSalesRate() {
        Data d = new Data();
        d.product(1, "Soon", "dairy", 5, null, 8, 2, 30);     // 42 in 14 days = 3/day → 2.67 days
        d.net14.put(1L, 42L);
        d.product(2, "Exactly3", "dairy", 5, null, 9, 2, 30); // 9 ÷ 3 = 3 days → HIGH
        d.net14.put(2L, 42L);
        d.product(3, "Week", "dairy", 5, null, 21, 2, 30);    // 7 days → MEDIUM (at risk only)
        d.net14.put(3L, 42L);
        d.product(4, "Few", "dairy", 5, null, 1, 5, 30);      // low stock; 2 units sold: not enough data
        d.net14.put(4L, 2L);
        d.product(5, "Young", "dairy", 5, null, 1, 0, 5);     // 5 days old: not enough data
        d.net14.put(5L, 30L);
        GuardianData g = d.build();
        List<Alert> a = new StockoutSoonRule().evaluate(g);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).params()).containsEntry("count", 2);
        assertThat(a.get(0).evidence()).extracting(r -> r.id()).containsExactly(1L, 2L);

        List<AtRiskProduct> risk = GuardianAnalysis.analyze(g).atRisk();
        AtRiskProduct soon = risk.stream().filter(r -> r.productId() == 1).findFirst().orElseThrow();
        assertThat(soon.risk()).isEqualTo(Severity.HIGH);
        assertThat(soon.daysLeft()).isEqualTo(2.7);
        assertThat(soon.expectedStockout()).isEqualTo(TODAY.plusDays(2));
        assertThat(risk.stream().filter(r -> r.productId() == 3).findFirst().orElseThrow().risk())
                .isEqualTo(Severity.MEDIUM);
        // Not enough data: low stock still listed, with no invented date.
        AtRiskProduct few = risk.stream().filter(r -> r.productId() == 4).findFirst().orElseThrow();
        assertThat(few.prediction()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(few.expectedStockout()).isNull();
        assertThat(few.risk()).isEqualTo(Severity.LOW);
    }

    // ── G7 cancellations ────────────────────────────────────────────────

    @Test
    void g7CountAndShareOfTheDay() {
        Data d = new Data();
        // Day 18: 2 canceled → nothing.
        d.cancel(1, at(18, 10), null, null);
        d.cancel(2, at(18, 11), null, null);
        // Day 19: 3 canceled, 7 completed (30%) → MEDIUM; canceled by Sara (started by Ahmad).
        for (long id = 10; id < 13; id++) d.cancel(id, at(19, 10), SARA, "Sara");
        for (long id = 20; id < 27; id++) d.invoice(id, 10, 0, at(19, 12), 1, 1, 0);
        // Day 20: 3 canceled, 47 completed (6%) → nothing.
        for (long id = 30; id < 33; id++) d.cancel(id, at(20, 10), null, null);
        for (long id = 40; id < 87; id++) d.invoice(id, 10, 0, at(20, 12), 1, 1, 0);
        // Day 21: 6 canceled, 4 completed (60%) → HIGH.
        for (long id = 100; id < 106; id++) d.cancel(id, at(21, 10), null, null);
        for (long id = 110; id < 114; id++) d.invoice(id, 10, 0, at(21, 12), 1, 1, 0);
        List<Alert> a = new CanceledInvoicesRule().evaluate(d.build());
        assertThat(a).extracting(Alert::key, Alert::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("G7:day:2026-11-19", Severity.MEDIUM),
                org.assertj.core.groups.Tuple.tuple("G7:day:2026-11-21", Severity.HIGH));
        Alert day19 = a.get(0);
        assertThat(day19.params()).containsEntry("count", 3).containsEntry("completed", 7)
                .containsEntry("percent", 30.0).containsEntry("notRecorded", 0);
        assertThat(day19.params().get("byUser")).asList().hasSize(1)
                .first().asString().contains("Sara").doesNotContain("Ahmad");
        assertThat(a.get(1).actorNote()).isEqualTo("NOT_RECORDED");
    }

    // ── G8 returns ──────────────────────────────────────────────────────

    @Test
    void g8aRefundsComparedWithTheDaysSales() {
        Data d = new Data();
        d.sale(1, 500, at(18, 9));
        d.returned(9, "Juice", 60, at(18, 15), SARA, "Sara");       // 12% → MEDIUM
        d.sale(2, 500, at(19, 9));
        d.returned(9, "Juice", 49.99, at(19, 15), SARA, "Sara");    // under 50 ₪ → nothing
        d.sale(3, 500, at(20, 9));
        d.returned(8, "Bread", 130, at(20, 15), AHMAD, "Ahmad");    // 26% → HIGH
        d.returned(7, "Rice", 50, at(21, 15), SARA, "Sara");        // no sales that day → MEDIUM
        d.sale(4, 100, at(22, 9));
        d.returned(6, "Oil", 40, at(22, 10), SARA, "Sara");         // 40% but only 40 ₪ → nothing
        List<Alert> a = only(new ReturnsRule().evaluate(d.build()), "G8a");
        assertThat(a).extracting(Alert::key, Alert::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("G8a:day:2026-11-18", Severity.MEDIUM),
                org.assertj.core.groups.Tuple.tuple("G8a:day:2026-11-20", Severity.HIGH),
                org.assertj.core.groups.Tuple.tuple("G8a:day:2026-11-21", Severity.MEDIUM));
        assertThat(a.get(0).params()).containsEntry("percent", 12.0).containsEntry("refunds", 60.0)
                .containsEntry("sales", 500.0);
        assertThat(a.get(1).params().get("byUser")).asList().first().asString().contains("Ahmad");
        assertThat(a.get(2).params().get("percent")).isNull();
    }

    @Test
    void g8bTheSameProductReturnedThreeTimesInSevenDays() {
        Data d = new Data();
        d.product(5, "Yogurt", "dairy", 3, null, 10, 2, 60);
        d.returned(5, "Yogurt", 3, at(10, 9), SARA, "Sara");
        d.returned(5, "Yogurt", 3, at(13, 9), SARA, "Sara");
        d.returned(5, "Yogurt", 3, at(16, 9), AHMAD, "Ahmad");
        // Spread over 10 days: never 3 within 7 days.
        d.returned(6, "Cheese", 3, at(1, 9), SARA, "Sara");
        d.returned(6, "Cheese", 3, at(6, 9), SARA, "Sara");
        d.returned(6, "Cheese", 3, at(11, 9), SARA, "Sara");
        List<Alert> a = only(new ReturnsRule().evaluate(d.build()), "G8b");
        assertThat(a).hasSize(1);
        assertThat(a.get(0).key()).isEqualTo("G8b:product:5");
        assertThat(a.get(0).params()).containsEntry("count", 3).containsEntry("days", 7);
        assertThat(a.get(0).actor()).isNull(); // about the product, not a person
        assertThat(a.get(0).action().id()).isEqualTo(5L);
    }

    // ── G9 deletions ────────────────────────────────────────────────────

    @Test
    void g9DeletionsBySameUserInOneDay() {
        Data d = new Data();
        for (int i = 0; i < 4; i++) d.deletion(ADMIN, "Omar", "P" + i, at(18, 10));
        for (int i = 0; i < 5; i++) d.deletion(ADMIN, "Omar", "Q" + i, at(19, 10));
        for (int i = 0; i < 10; i++) d.deletion(ADMIN, "Omar", "R" + i, at(20, 10));
        for (int i = 0; i < 3; i++) d.deletion(ADMIN, "Omar", "S" + i, at(21, 10));
        for (int i = 0; i < 3; i++) d.deletion(9, "Lina", "T" + i, at(21, 11));
        List<Alert> a = new ProductDeletionsRule().evaluate(d.build());
        assertThat(a).extracting(Alert::key, Alert::severity).containsExactly(
                org.assertj.core.groups.Tuple.tuple("G9:user:3:2026-11-19", Severity.MEDIUM),
                org.assertj.core.groups.Tuple.tuple("G9:user:3:2026-11-20", Severity.HIGH));
        assertThat(a.get(0).actor().name()).isEqualTo("Omar");
        assertThat(a.get(0).params().get("products")).asList().containsExactly("Q0", "Q1", "Q2", "Q3", "Q4");
    }

    // ── T1 / T2 / T3 ────────────────────────────────────────────────────

    @Test
    void recommendationsReorderLowMarginAndSlowMoving() {
        Data d = new Data();
        d.product(1, "Soon", "dairy", 5, null, 8, 2, 30);
        d.net14.put(1L, 42L);
        d.sold30.add(1L);
        d.product(2, "Thin", "dairy", 10, 9.5, 50, 2, 60);      // 5% margin
        d.product(3, "TenPercent", "dairy", 10, 9.0, 50, 2, 60); // exactly 10% → not low
        d.product(4, "Loss", "dairy", 9, 10.0, 50, 2, 60);      // price < cost → G3, not T2
        d.product(5, "Dusty", "dairy", 4, null, 30, 5, 40);     // 40 days, nothing sold in 30 days
        d.product(6, "Sold", "dairy", 4, null, 30, 5, 40);
        d.sold30.add(6L);
        d.product(7, "New", "dairy", 4, null, 30, 5, 20);       // only 20 days old
        d.product(8, "AtMin", "dairy", 4, null, 5, 5, 40);      // not more than the minimum
        GuardianData g = d.build();
        List<Recommendation> r = GuardianAnalysis.analyze(g).recommendations();
        assertThat(r).extracting(Recommendation::code).containsExactly("REORDER_SOON", "LOW_MARGIN", "SLOW_MOVING");
        assertThat(r.get(0).count()).isEqualTo(1);
        assertThat(r.get(0).products().get(0)).containsEntry("name", "Soon").containsEntry("daysLeft", 2.7);
        assertThat(r.get(0).action().type()).isEqualTo("STOCK_ALERTS");
        assertThat(r.get(1).products()).extracting(m -> m.get("name")).containsExactly("Thin");
        assertThat(r.get(1).products().get(0)).containsEntry("marginPercent", 5.0);
        // Products 2, 3 and 4 are 60 days old and were never sold either, so they are listed too.
        assertThat(r.get(2).products()).extracting(m -> m.get("name"))
                .contains("Dusty").doesNotContain("Sold", "New", "AtMin");
    }

    // ── Store Health Score (final plan, part 5) ─────────────────────────

    @Nested
    class Health_ {

        /** 40 products (34 healthy); 120 invoices over 20 days, 90 fully costed with 2 losses. */
        Data example(int fullyCosted) {
            Data d = new Data();
            for (int i = 1; i <= 40; i++) d.product(i, "P" + i, "dairy", 10, null, i <= 34 ? 50 : 1, 5, 60);
            for (int i = 1; i <= 120; i++) {
                boolean costed = i <= fullyCosted;
                boolean loss = i <= 2;
                d.invoice(i, 100, 0, at(1 + (i - 1) % 20, 10), 1, costed ? 0 : 1, loss ? 101 : 50);
            }
            return d;
        }

        static List<Alert> twoActivityDays() {
            return List.of(
                    new Alert("G7:day:2026-11-03", "G7", "CANCELED_INVOICES", Severity.MEDIUM, "ACTIVITY", Map.of(),
                            List.of(), null, null, at(3, 10), LocalDate.of(2026, 11, 3), null),
                    new Alert("G9:user:3:2026-11-05", "G9", "PRODUCT_DELETIONS", Severity.MEDIUM, "ACTIVITY", Map.of(),
                            List.of(), null, null, at(5, 10), LocalDate.of(2026, 11, 5), null));
        }

        @Test
        void theWorkedExampleOfThePlanGives92() {
            Health h = HealthScore.of(example(90).build(), 6, twoActivityDays());
            assertThat(h.parts()).extracting(p -> p.points()).containsExactly(21.25, 24.44, 23.75, 22.5);
            assertThat(h.score()).isEqualTo(92);
            assertThat(h.band()).isEqualTo("GOOD");
            assertThat(h.partsUsed()).isEqualTo(4);
        }

        @Test
        void lowCostCoverageMakesProfitUnavailableAndTheScoreUsesThreeParts() {
            Health h = HealthScore.of(example(36).build(), 6, twoActivityDays()); // 30% coverage
            assertThat(h.parts().get(1).available()).isFalse();
            assertThat(h.parts().get(1).reason()).isEqualTo("COST_COVERAGE_LOW");
            assertThat(h.parts().get(1).numbers()).containsEntry("coveragePercent", 30);
            assertThat(h.partsUsed()).isEqualTo(3);
            assertThat(h.score()).isEqualTo(90);
        }

        @Test
        void aNewStoreWithOnlyProductsHasNoScore() {
            Data d = new Data();
            d.product(1, "Milk", "dairy", 5, null, 10, 2, 1);
            d.invoice(1, 10, 0, at(20, 10), 1, 0, 5);
            Health h = HealthScore.of(d.build(), 0, List.of());
            assertThat(h.score()).isNull();
            assertThat(h.band()).isNull();
            assertThat(h.reason()).isEqualTo("INSUFFICIENT_DATA");
            assertThat(h.partsUsed()).isEqualTo(1);
            assertThat(h.parts()).extracting(p -> p.reason())
                    .containsExactly(null, "NOT_ENOUGH_INVOICES", "NOT_ENOUGH_INVOICES", "NOT_ENOUGH_INVOICES");
        }

        @Test
        void aPastMonthHasNoStockPartAndAHealthyStoreScores100() {
            Data d = example(120);
            d.invoices.removeIf(i -> i.saleId() <= 2); // no losses
            d.products.replaceAll(p -> new ProductFact(p.id(), p.name(), p.category(), p.price(), p.cost(), 50,
                    p.minStock(), p.createdAt()));
            assertThat(HealthScore.of(d.build(), 0, List.of()).score()).isEqualTo(100);
            d.current = false;
            Health past = HealthScore.of(d.build(), 0, List.of());
            assertThat(past.parts().get(0).reason()).isEqualTo("PAST_PERIOD");
            assertThat(past.partsUsed()).isEqualTo(3);
        }

        @Test
        void bands() {
            assertThat(HealthScore.band(80)).isEqualTo("GOOD");
            assertThat(HealthScore.band(79)).isEqualTo("ATTENTION");
            assertThat(HealthScore.band(60)).isEqualTo("ATTENTION");
            assertThat(HealthScore.band(59)).isEqualTo("RISK");
        }
    }

    // ── Store map and activity summary ──────────────────────────────────

    @Test
    void storeMapHasOneTilePerCategoryAndTheCashierArea() {
        Data d = new Data();
        d.product(1, "Milk", "dairy", 5, null, 50, 5, 60);
        d.product(2, "Cheese", "dairy", 5, null, 1, 5, 60);
        d.product(3, "Cola", "beverages", 5, null, 50, 5, 60);
        d.invoice(1, 100, 9, at(20, 10), 1, 1, 0); // G1 MEDIUM → cashier area needs attention
        List<MapTile> map = GuardianAnalysis.analyze(d.build()).storeMap();
        assertThat(map).extracting(MapTile::category, MapTile::percent, MapTile::band).containsExactly(
                org.assertj.core.groups.Tuple.tuple("beverages", 100, "GOOD"),
                org.assertj.core.groups.Tuple.tuple("dairy", 50, "RISK"),
                org.assertj.core.groups.Tuple.tuple(null, null, "ATTENTION"));
        assertThat(map.get(2).kind()).isEqualTo("CASHIER_AREA");
        assertThat(map.get(2).mediumAlerts()).isEqualTo(1);
    }

    @Test
    void activitySummaryCountsOnlyCasesThatBelongToAnAlert() {
        Data d = new Data();
        d.invoice(1, 100, 9, at(20, 10), 1, 1, 0);                      // 1 high discount
        for (long id = 10; id < 13; id++) d.cancel(id, at(19, 10), null, null); // flagged day (no sales)
        d.cancel(20, at(18, 10), null, null);                            // 1 cancel: not flagged
        for (int i = 0; i < 5; i++) d.deletion(ADMIN, "Omar", "P" + i, at(17, 10));
        var s = GuardianAnalysis.analyze(d.build()).activity();
        assertThat(s.highDiscounts()).isEqualTo(1);
        assertThat(s.cancellations()).isEqualTo(3);
        assertThat(s.deletions()).isEqualTo(5);
        assertThat(s.returns()).isZero();
        assertThat(s.total()).isEqualTo(9);
        assertThat(s.days()).isEqualTo(22);
        assertThat(s.perDay()).isEqualTo(0.4);
    }

    @Test
    void alertsAreSortedBySeverityThenNewestAndCountedForTheCards() {
        Data d = new Data();
        d.product(1, "Out", "dairy", 5, 6.0, 0, 5, 60);   // G5 HIGH + G3 HIGH
        d.invoice(1, 100, 9, at(20, 10), 1, 1, 0);        // G1 MEDIUM
        GuardianAnalysis.Result r = GuardianAnalysis.analyze(d.build());
        assertThat(r.alerts()).extracting(Alert::severity)
                .containsExactly(Severity.HIGH, Severity.HIGH, Severity.MEDIUM);
        assertThat(r.criticalCount()).isEqualTo(2);
        assertThat(r.profitRiskCount()).isEqualTo(1);
        assertThat(r.costData().productsWithoutCost()).isZero();
    }
}
