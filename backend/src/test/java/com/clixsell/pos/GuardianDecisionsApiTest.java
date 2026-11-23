package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardian Mode – final management decisions (U2, U3, U4, U8, G8b across months).
 * Each test was written first and failed on the code before the change.
 */
class GuardianDecisionsApiTest extends GuardianApiTestBase {

    private StoreSetup shini;

    @BeforeEach
    void store() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> alert) {
        return (Map<String, Object>) alert.get("params");
    }

    // ── U2: the user's role is shown, so a cashier is never blamed for a Store Admin's action ──

    @Test
    void u2TheRecordedUserComesWithTheirRole() throws Exception {
        long milk = product(shini.adminToken(), "Milk", "11111111", "dairy", 5, 3.2, 40, 5);
        sell(shini.adminToken(), Map.of(milk, 10), "PERCENT", 10.0); // the Store Admin sells with a discount
        setGuardian(shini.adminToken(), true);
        String all = getJson(shini.adminToken(), "/api/guardian/alerts?rule=G1");
        assertThat(read(all, "$.alerts[0].actor.role").toString()).isEqualTo("STORE_ADMIN");
        // The cashier area tile counts point-of-sale alerts of ANY user.
        String d = getJson(shini.adminToken(), "/api/guardian/dashboard");
        assertThat(read(d, "$.storeMap[?(@.kind == 'CASHIER_AREA')].mediumAlerts").toString()).isEqualTo("[1]");
    }

    // ── U3: a high score never looks "Good" while critical alerts are open ──

    @Test
    void u3CriticalAlertsLimitTheBandButNotTheScore() throws Exception {
        long water = product(shini.adminToken(), "Water", "66666666", "beverages", 2, 1.0, 200, 5);
        for (int i = 0; i < 6; i++) sell(shini.cashierToken(), Map.of(water, 2), null, null);
        product(shini.adminToken(), "Promo Cake", "33333333", "bakery", 2, 3.0, 30, 2); // G3 HIGH, never sold
        setGuardian(shini.adminToken(), true);
        String d = getJson(shini.adminToken(), "/api/guardian/dashboard");
        assertThat((Integer) read(d, "$.health.score")).isEqualTo(100); // the formula is unchanged
        assertThat((String) read(d, "$.health.band")).isEqualTo("ATTENTION");
        assertThat((Boolean) read(d, "$.health.bandLimited")).isTrue();
        assertThat((Integer) read(d, "$.health.criticalAlerts")).isEqualTo(1);
    }

    // ── U4: allocation-based product margin risk (MEDIUM), never a confirmed invoice loss ──

    @Test
    void u4ALineBelowCostAfterItsShareOfTheDiscountInAProfitableInvoice() throws Exception {
        long milk = product(shini.adminToken(), "Milk", "11111111", "dairy", 5, 4.6, 40, 5);
        long choc = product(shini.adminToken(), "Chocolate", "22222222", "snacks", 20, 8.0, 40, 5);
        long bread = product(shini.adminToken(), "Bread", "44444444", "bakery", 5, null, 40, 5);
        // Profitable invoice: 25.00 − 2.50 = 22.50 > cost 12.60; Milk's share 4.50 < 4.60.
        long mixed = sell(shini.cashierToken(), Map.of(milk, 1, choc, 1), "PERCENT", 10.0);
        returnOne(shini.cashierToken(), mixed, milk);                      // returned later
        // Bread has no cost: Milk's share is still known (5 × 9 / 10 = 4.50); Bread's cost is never guessed.
        sell(shini.cashierToken(), Map.of(milk, 1, bread, 1), "PERCENT", 10.0);
        // Milk alone with 10%: the whole invoice is a confirmed loss → G2b only, no second alert.
        long loss = sell(shini.cashierToken(), Map.of(milk, 1), "PERCENT", 10.0);
        sell(shini.cashierToken(), Map.of(milk, 1, choc, 1), null, null); // no discount → nothing
        setGuardian(shini.adminToken(), true);

        List<Map<String, Object>> g2c = alerts(getJson(shini.adminToken(), "/api/guardian/alerts?rule=G2c"));
        assertThat(g2c).hasSize(1); // one per product per day
        Map<String, Object> a = g2c.get(0);
        assertThat(a.get("code")).isEqualTo("DISCOUNT_SHARE_BELOW_COST");
        assertThat(a.get("severity")).isEqualTo("MEDIUM");
        assertThat(params(a)).containsEntry("productName", "Milk").containsEntry("times", 2).containsEntry("units", 2)
                .containsEntry("unitsReturned", 1);
        assertThat(((Number) params(a).get("allocatedUnitRevenue")).doubleValue()).isEqualTo(4.5);
        assertThat(((Number) params(a).get("shortfall")).doubleValue()).isEqualTo(0.2);
        // The confirmed-loss invoice is G2b, not G2c; the invoice totals are not losses.
        String g2b = getJson(shini.adminToken(), "/api/guardian/alerts?rule=G2b");
        assertThat(read(g2b, "$.alerts[*].action.id").toString()).isEqualTo("[" + loss + "]");
        assertThat(read(getJson(shini.adminToken(), "/api/guardian/dashboard"), "$.costData.confirmedLossInvoices")
                .toString()).isEqualTo("1");

        // Another store sees none of it.
        StoreSetup neshan = setupStore("Neshan Market", "neshan");
        setGuardian(neshan.adminToken(), true);
        assertThat(alerts(getJson(neshan.adminToken(), "/api/guardian/alerts?rule=G2c"))).isEmpty();
    }

    // ── U8: several alerts about one incident are linked, and the critical card counts incidents ──

    @Test
    void u8AlertsAboutTheSameInvoiceOrProductAreLinked() throws Exception {
        long cake = product(shini.adminToken(), "Promo Cake", "33333333", "bakery", 2, 3.0, 30, 2);
        sell(shini.cashierToken(), Map.of(cake, 5), null, null); // G3 + G2a + G2b, one incident
        product(shini.adminToken(), "Eggs", "55555555", "groceries", 15, 9.0, 0, 5); // G5, another
        setGuardian(shini.adminToken(), true);
        String d = getJson(shini.adminToken(), "/api/guardian/dashboard");
        assertThat((Integer) read(d, "$.criticalCount")).isEqualTo(4);
        assertThat((Integer) read(d, "$.criticalIssues")).isEqualTo(2);
        String all = getJson(shini.adminToken(), "/api/guardian/alerts");
        assertThat(read(all, "$.alerts[?(@.rule == 'G2b')].related[*]").toString())
                .contains("G2a:product:", "G3:product:");
        assertThat(read(all, "$.alerts[?(@.rule == 'G5')].related").toString()).isEqualTo("[[]]");
    }

    /**
     * U8 (audit part 1): one cashier gives 3 high discounts in a day (G1 "repeated", HIGH) and one of
     * those invoices is also a confirmed loss (G2b, HIGH). The invoice stays in both alerts, but they
     * are linked and counted as ONE critical issue.
     */
    @Test
    void u8ARepeatedDiscountAlertAndTheLossOfOneOfItsInvoicesAreOneIssue() throws Exception {
        long milk = product(shini.adminToken(), "Milk", "11111111", "dairy", 5, 1.0, 100, 5);
        long cream = product(shini.adminToken(), "Cream", "22222222", "dairy", 5, 4.6, 100, 5);
        sell(shini.cashierToken(), Map.of(milk, 10), "PERCENT", 10.0);
        sell(shini.cashierToken(), Map.of(milk, 10), "PERCENT", 10.0);
        long loss = sell(shini.cashierToken(), Map.of(cream, 10), "PERCENT", 10.0); // 45.00 < cost 46.00
        setGuardian(shini.adminToken(), true);
        String all = getJson(shini.adminToken(), "/api/guardian/alerts?severity=HIGH");
        assertThat(read(all, "$.alerts[*].key").toString()).contains("G1:user:", "G2b:sale:" + loss);
        assertThat(read(all, "$.alerts[?(@.rule == 'G1')].params.count").toString()).isEqualTo("[3]");
        assertThat(read(all, "$.alerts[?(@.rule == 'G2b')].related[*]").toString()).contains("G1:user:");
        assertThat(read(all, "$.alerts[?(@.rule == 'G1')].related[*]").toString()).contains("G2b:sale:" + loss);
        String d = getJson(shini.adminToken(), "/api/guardian/dashboard");
        assertThat((Integer) read(d, "$.criticalCount")).isEqualTo(2);
        assertThat((Integer) read(d, "$.criticalIssues")).isEqualTo(1);
    }

    // ── G8b across two calendar months, without counting an episode twice ──

    private long oldSale(long product, int quantity, LocalDate day) throws Exception {
        long sale = sell(shini.cashierToken(), Map.of(product, quantity), null, null);
        String at = day + "T09:00:00";
        db.update("UPDATE sales SET completed_at = ?, created_at = ?, updated_at = ? WHERE sale_id = ?", at, at, at, sale);
        return sale;
    }

    private void returnOn(long sale, long product, LocalDate day) {
        long item = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ? AND product_id = ?",
                Long.class, sale, product);
        long cashier = db.queryForObject("SELECT cashier_id FROM sales WHERE sale_id = ?", Long.class, sale);
        db.update("INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                        + "refund_amount, refund_method, return_date) VALUES (?, ?, ?, ?, ?, 1, 'Damaged', 5.80, 'CASH', ?)",
                shini.storeId(), sale, item, product, cashier, day + "T12:00:00");
        db.update("UPDATE sale_items SET returned_quantity = returned_quantity + 1 WHERE sale_item_id = ?", item);
    }

    @Test
    void g8bReturnsAcrossTwoMonthsAreFoundOnceInTheMonthTheLimitIsReached() throws Exception {
        YearMonth now = YearMonth.now();
        YearMonth prev = now.minusMonths(1);
        LocalDate last = prev.atEndOfMonth();
        long juice = product(shini.adminToken(), "Juice", "77777777", "beverages", 5, 3.0, 50, 5);
        long jam = product(shini.adminToken(), "Jam", "88888888", "groceries", 5, 3.0, 50, 5);
        // Juice: 2 returns at the end of last month + 1 on the 1st → the limit (3 in 7 days) is reached this month.
        long s1 = oldSale(juice, 5, last.minusDays(5));
        returnOn(s1, juice, last.minusDays(1));
        returnOn(s1, juice, last);
        returnOn(s1, juice, now.atDay(1));
        // Jam: 3 returns at the end of last month (alert there) + 1 on the 1st (same episode, not again).
        long s2 = oldSale(jam, 5, last.minusDays(6));
        returnOn(s2, jam, last.minusDays(2));
        returnOn(s2, jam, last.minusDays(1));
        returnOn(s2, jam, last);
        returnOn(s2, jam, now.atDay(1));
        setGuardian(shini.adminToken(), true);

        String thisMonth = getJson(shini.adminToken(), "/api/guardian/alerts?rule=G8b");
        assertThat(read(thisMonth, "$.alerts[*].params.productName").toString()).isEqualTo("[\"Juice\"]");
        assertThat(read(thisMonth, "$.alerts[0].params.count").toString()).isEqualTo("3");
        String lastMonth = getJson(shini.adminToken(),
                "/api/guardian/alerts?rule=G8b&year=" + prev.getYear() + "&month=" + prev.getMonthValue());
        assertThat(read(lastMonth, "$.alerts[*].params.productName").toString()).isEqualTo("[\"Jam\"]");
        assertThat(read(lastMonth, "$.alerts[0].params.count").toString()).isEqualTo("3");
    }
}
