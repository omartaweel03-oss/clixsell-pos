package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guardian Mode API (Figure 3.1) end to end on the real application and a test
 * database: roles, two separate stores, on / off, and every rule fed by real
 * sales, returns, cancellations and deletions made through the API.
 */
class GuardianApiTest extends GuardianApiTestBase {

    private static final List<String> GETS = List.of("/api/guardian/status", "/api/guardian/dashboard",
            "/api/guardian/alerts", "/api/guardian/activity");

    private StoreSetup shini;
    private StoreSetup neshan;

    @BeforeEach
    void stores() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
    }

    // ── Security ────────────────────────────────────────────────────────

    @Test
    void onlyTheStoreAdminMayUseGuardianMode() throws Exception {
        String sys = sysadminToken();
        for (String url : GETS) {
            mvc.perform(get(url).header("Authorization", bearer(shini.adminToken()))).andExpect(status().isOk());
            mvc.perform(get(url).header("Authorization", bearer(shini.cashierToken()))).andExpect(status().isForbidden());
            mvc.perform(get(url).header("Authorization", bearer(sys))).andExpect(status().isForbidden());
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
        for (String token : List.of(shini.cashierToken(), sys)) {
            mvc.perform(put("/api/guardian/status").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(put("/api/guardian/status").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM stores WHERE guardian_enabled = 1", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activity_log", Integer.class)).isZero();
    }

    // ── On / off (UR-M16 / SR30) ────────────────────────────────────────

    @Test
    void offByDefaultThenOnAndOffPerStoreWithHistory() throws Exception {
        String off = getJson(shini.adminToken(), "/api/guardian/dashboard");
        assertThat((Boolean) read(off, "$.enabled")).isFalse();
        assertThat((List<?>) read(off, "$.topAlerts")).isEmpty();
        assertThat((Object) read(off, "$.health")).isNull();
        assertThat((Object) read(off, "$.status.changedAt")).isNull();
        assertThat((String) read(off, "$.status.activityTrackedSince")).isNotNull();
        mvc.perform(get("/api/guardian/alerts").header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.alerts").isEmpty());
        mvc.perform(get("/api/guardian/activity").header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.entries").isEmpty());

        setGuardian(shini.adminToken(), true);
        setGuardian(shini.adminToken(), true); // same state again: no second row
        mvc.perform(get("/api/guardian/status").header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.changedBy").value("Test User"))
                .andExpect(jsonPath("$.changedAt").exists());
        // The other store is untouched.
        mvc.perform(get("/api/guardian/status").header("Authorization", bearer(neshan.adminToken())))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.changedAt").value(nullValue()));
        assertThat((Boolean) read(getJson(shini.adminToken(), "/api/guardian/dashboard"), "$.enabled")).isTrue();

        setGuardian(shini.adminToken(), false);
        long admin = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.admin'", Long.class);
        assertThat(db.queryForList("SELECT event_type FROM activity_log WHERE store_id = ? AND user_id = ? ORDER BY log_id",
                String.class, shini.storeId(), admin)).containsExactly("GUARDIAN_ENABLED", "GUARDIAN_DISABLED");
        assertThat(db.queryForObject("SELECT guardian_enabled FROM stores WHERE store_id = ?", Integer.class,
                shini.storeId())).isZero();
    }

    @Test
    void wrongOnOffRequestsAreRefused() throws Exception {
        for (String body : List.of("{}", "{\"enabled\":null}", "{\"enabled\":\"maybe\"}", "")) {
            mvc.perform(put("/api/guardian/status").header("Authorization", bearer(shini.adminToken()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(db.queryForObject("SELECT guardian_enabled FROM stores WHERE store_id = ?", Integer.class,
                shini.storeId())).isZero();
    }

    @Test
    void wrongPeriodsAndFiltersAreRefused() throws Exception {
        setGuardian(shini.adminToken(), true);
        YearMonth next = YearMonth.now().plusMonths(1);
        for (String q : List.of("?month=13&year=2026", "?month=0&year=2026", "?year=2026", "?month=5",
                "?year=1999&month=1", "?year=" + next.getYear() + "&month=" + next.getMonthValue(), "?year=abc&month=1")) {
            for (String url : List.of("/api/guardian/dashboard", "/api/guardian/alerts", "/api/guardian/activity")) {
                mvc.perform(get(url + q).header("Authorization", bearer(shini.adminToken())))
                        .andExpect(status().isBadRequest());
            }
        }
        for (String url : List.of("/api/guardian/alerts?severity=URGENT", "/api/guardian/alerts?rule=G99",
                "/api/guardian/activity?type=LOGIN", "/api/guardian/activity?page=-1")) {
            mvc.perform(get(url).header("Authorization", bearer(shini.adminToken()))).andExpect(status().isBadRequest());
        }
        // A past month is fine: current stock does not apply to it.
        YearMonth last = YearMonth.now().minusMonths(1);
        String past = getJson(shini.adminToken(),
                "/api/guardian/dashboard?year=" + last.getYear() + "&month=" + last.getMonthValue());
        assertThat((Boolean) read(past, "$.currentPeriod")).isFalse();
        assertThat((String) read(past, "$.health.parts[0].reason")).isEqualTo("PAST_PERIOD");
        assertThat((String) read(past, "$.to")).isEqualTo(last.atEndOfMonth().toString());
    }

    // ── Rules fed by real API actions ───────────────────────────────────

    @Test
    void everyRuleFromRealSalesReturnsCancellationsAndDeletions() throws Exception {
        Object[] lina = namedCashier(shini.adminToken(), "shini.lina", "Lina Haddad");
        String linaToken = (String) lina[1];
        long milk = product(shini.adminToken(), "Milk", "11111111", "dairy", 5, 3.2, 40, 5);
        long cheese = product(shini.adminToken(), "Cheese", "22222222", "dairy", 10, 9.5, 30, 5); // G4 + T2
        long promo = product(shini.adminToken(), "Promo Cake", "33333333", "bakery", 2, 3.0, 20, 2); // G3
        long bread = product(shini.adminToken(), "Bread", "44444444", "bakery", 2, null, 13, 10); // no cost
        product(shini.adminToken(), "Eggs", "55555555", "groceries", 15, 9.0, 0, 5); // out of stock

        // G1: 10% (5.00 ₪) on 50 ₪, completed by Lina; profit is fine (cost 32 < 45).
        long discounted = sell(linaToken, Map.of(milk, 10), "PERCENT", 10.0);
        // G2a + G2b: Promo Cake sold at 2.00 with a cost of 3.00 → confirmed loss of 5.00.
        long loss = sell(shini.cashierToken(), Map.of(promo, 5), null, null);
        // A line without cost → INCOMPLETE_COST, never a loss (Bread 2 has no cost).
        sell(shini.cashierToken(), Map.of(bread, 3, milk, 1), null, null); // Bread → 10 left = low stock
        // A return of the discounted sale (processed by the cashier).
        returnOne(shini.cashierToken(), discounted, milk);
        // G7: 3 suspended invoices canceled by Lina today (3 of 6 invoices = 50%).
        for (int i = 0; i < 3; i++) cancel(linaToken, suspend(shini.cashierToken(), Map.of(milk, 1), null, null));
        // G9: 5 products deleted by the Store Admin today.
        for (int i = 0; i < 5; i++) {
            deleteProduct(shini.adminToken(), product(shini.adminToken(), "Old " + i, "9000000" + i, "other", 1, null, 1, 0));
        }

        setGuardian(shini.adminToken(), true);
        String all = getJson(shini.adminToken(), "/api/guardian/alerts");
        assertThat(alerts(all)).extracting(a -> a.get("code")).contains("HIGH_DISCOUNT", "BELOW_COST_SALE",
                "CONFIRMED_LOSS", "PRICE_BELOW_COST", "MAX_DISCOUNT_RISK", "OUT_OF_STOCK", "LOW_STOCK",
                "CANCELED_INVOICES", "PRODUCT_DELETIONS");

        Map<String, Object> g1 = alert(all, "HIGH_DISCOUNT");
        assertThat(g1.get("severity")).isEqualTo("MEDIUM");
        assertThat(read(all, "$.alerts[?(@.code == 'HIGH_DISCOUNT')].actor.name").toString()).contains("Lina Haddad");
        assertThat(read(all, "$.alerts[?(@.code == 'HIGH_DISCOUNT')].params.percent").toString()).contains("10.0");
        assertThat(read(all, "$.alerts[?(@.code == 'HIGH_DISCOUNT')].action.id").toString())
                .contains(String.valueOf(discounted));

        assertThat(read(all, "$.alerts[?(@.code == 'CONFIRMED_LOSS')].params.loss").toString()).contains("5.0");
        assertThat(read(all, "$.alerts[?(@.code == 'CONFIRMED_LOSS')].action.id").toString())
                .contains(String.valueOf(loss));
        assertThat(read(all, "$.alerts[?(@.code == 'CANCELED_INVOICES')].params.count").toString()).contains("3");
        assertThat(read(all, "$.alerts[?(@.code == 'CANCELED_INVOICES')].params.byUser[0].name").toString())
                .contains("Lina Haddad");
        assertThat(read(all, "$.alerts[?(@.code == 'PRODUCT_DELETIONS')].params.count").toString()).contains("5");
        assertThat(read(all, "$.alerts[?(@.code == 'MAX_DISCOUNT_RISK')].params.safePercent").toString()).contains("5.0");

        String d = getJson(shini.adminToken(), "/api/guardian/dashboard");
        assertThat((Boolean) read(d, "$.enabled")).isTrue();
        assertThat((Integer) read(d, "$.profitRiskCount")).isEqualTo(3); // G2a, G2b, G3
        assertThat((Integer) read(d, "$.costData.incompleteCostInvoices")).isEqualTo(1);
        assertThat((Integer) read(d, "$.costData.confirmedLossInvoices")).isEqualTo(1);
        assertThat((Integer) read(d, "$.costData.productsWithoutCost")).isEqualTo(1);
        assertThat((List<?>) read(d, "$.topAlerts")).hasSize(3);
        assertThat((Integer) read(d, "$.alertCount")).isEqualTo(alerts(all).size());
        int high = (int) alerts(all).stream().filter(a -> "HIGH".equals(a.get("severity"))).count();
        assertThat((Integer) read(d, "$.criticalCount")).isEqualTo(high);
        // Only 3 completed invoices: not enough data for a health number.
        assertThat((Object) read(d, "$.health.score")).isNull();
        assertThat((String) read(d, "$.health.reason")).isEqualTo("INSUFFICIENT_DATA");
        assertThat((Boolean) read(d, "$.health.parts[0].available")).isTrue();
        // Activity cases: 1 high discount, 3 cancellations, 5 deletions (the return is not unusual).
        assertThat((Integer) read(d, "$.activity.highDiscounts")).isEqualTo(1);
        assertThat((Integer) read(d, "$.activity.cancellations")).isEqualTo(3);
        assertThat((Integer) read(d, "$.activity.deletions")).isEqualTo(5);
        assertThat((Integer) read(d, "$.activity.returns")).isZero();
        // Today's net sales = paid − refund, as Sales Analytics.
        double paid = db.queryForObject("SELECT SUM(total) FROM sales WHERE store_id = ? AND status = 'COMPLETED'",
                Double.class, shini.storeId());
        double refund = db.queryForObject("SELECT SUM(refund_amount) FROM returns WHERE store_id = ?", Double.class,
                shini.storeId());
        assertThat(((Number) read(d, "$.todaySales.today")).doubleValue())
                .isEqualTo(Math.round((paid - refund) * 100) / 100.0);
        assertThat((List<?>) read(d, "$.todaySales.last7Days")).hasSize(7);
        // Map: one tile per category with products + the cashier area (G1 MEDIUM, G7 MEDIUM → attention).
        assertThat(read(d, "$.storeMap[*].category").toString()).contains("bakery", "dairy", "groceries");
        assertThat(read(d, "$.storeMap[?(@.kind == 'CASHIER_AREA')].band").toString()).contains("ATTENTION");
        assertThat(read(d, "$.recommendations[*].code").toString())
                .contains("REORDER_SOON", "LOW_MARGIN", "SLOW_MOVING");
        assertThat(read(d, "$.recommendations[?(@.code == 'LOW_MARGIN')].products[*].name").toString()).contains("Cheese");
        assertThat(read(d, "$.profitProtection[*].status").toString())
                .contains("PRICE_BELOW_COST", "MAX_DISCOUNT_RISK");
        assertThat(d).doesNotContain("password").doesNotContain("token");

        // The other store sees nothing of Shini, even with Guardian on.
        setGuardian(neshan.adminToken(), true);
        String other = getJson(neshan.adminToken(), "/api/guardian/alerts");
        assertThat(alerts(other)).isEmpty();
        String otherDash = getJson(neshan.adminToken(), "/api/guardian/dashboard");
        assertThat(otherDash).doesNotContain("Milk", "Promo Cake", "Lina Haddad", "Cheese");
        assertThat((Integer) read(otherDash, "$.costData.invoices")).isZero();
        String otherLog = getJson(neshan.adminToken(), "/api/guardian/activity");
        assertThat((List<?>) read(otherLog, "$.entries")).hasSize(1); // only its own GUARDIAN_ENABLED
    }

    @Test
    void stockoutForecastSlowMovingAndAHealthNumberFromEnoughData() throws Exception {
        long water = product(shini.adminToken(), "Water", "66666666", "beverages", 2, 1.0, 50, 5);
        long dusty = product(shini.adminToken(), "Dusty Jam", "77777777", "groceries", 9, 4.0, 30, 5);
        // Both products were added 30 / 40 days ago (test database only).
        db.update("UPDATE products SET created_at = ? WHERE product_id = ?",
                LocalDateTime.now().minusDays(30).withNano(0).toString(), water);
        db.update("UPDATE products SET created_at = ? WHERE product_id = ?",
                LocalDateTime.now().minusDays(40).withNano(0).toString(), dusty);
        for (int i = 0; i < 7; i++) sell(shini.cashierToken(), Map.of(water, 6), null, null); // 42 sold → 8 left

        setGuardian(shini.adminToken(), true);
        String d = getJson(shini.adminToken(), "/api/guardian/dashboard");
        // 42 units in 14 days = 3 a day; 8 left → about 2.7 days.
        assertThat(read(d, "$.atRiskProducts[?(@.name == 'Water')].risk").toString()).contains("HIGH");
        assertThat(read(d, "$.atRiskProducts[?(@.name == 'Water')].daysLeft").toString()).contains("2.7");
        assertThat(read(d, "$.atRiskProducts[?(@.name == 'Water')].expectedStockout").toString())
                .contains(java.time.LocalDate.now().plusDays(2).toString());
        assertThat(read(d, "$.recommendations[?(@.code == 'REORDER_SOON')].products[*].name").toString()).contains("Water");
        assertThat(read(d, "$.recommendations[?(@.code == 'SLOW_MOVING')].products[*].name").toString())
                .contains("Dusty Jam").doesNotContain("Water");
        String all = getJson(shini.adminToken(), "/api/guardian/alerts?rule=G6");
        assertThat(alerts(all)).hasSize(1);
        assertThat(alerts(all).get(0).get("code")).isEqualTo("STOCKOUT_SOON");
        // 7 fully-costed invoices, no loss, no high discount, no unusual activity, healthy stock → 100.
        assertThat((Integer) read(d, "$.health.score")).isEqualTo(100);
        assertThat((Integer) read(d, "$.health.partsUsed")).isEqualTo(4);
        // Decision U3: the score stays 100, but the open HIGH alert (Water runs out, G6) limits the band.
        assertThat((String) read(d, "$.health.band")).isEqualTo("ATTENTION");
        assertThat((Boolean) read(d, "$.health.bandLimited")).isTrue();
        assertThat((Integer) read(d, "$.health.criticalAlerts")).isEqualTo(1);
        // Severity filter.
        String high = getJson(shini.adminToken(), "/api/guardian/alerts?severity=HIGH");
        assertThat(alerts(high)).allMatch(a -> "HIGH".equals(a.get("severity")));
    }

    @Test
    void activityReportListsTheStoresOwnEventsNewestFirst() throws Exception {
        long a = product(shini.adminToken(), "Alpha", "12121212", "other", 3, null, 5, 1);
        long b = product(shini.adminToken(), "Beta", "13131313", "other", 3, null, 5, 1);
        deleteProduct(shini.adminToken(), a);
        deleteProduct(shini.adminToken(), b);
        product(neshan.adminToken(), "Gamma", "14141414", "other", 3, null, 5, 1);
        setGuardian(shini.adminToken(), true);

        String page = getJson(shini.adminToken(), "/api/guardian/activity");
        assertThat((Integer) read(page, "$.total")).isEqualTo(3);
        assertThat(read(page, "$.entries[*].type").toString())
                .isEqualTo("[\"GUARDIAN_ENABLED\",\"PRODUCT_DELETED\",\"PRODUCT_DELETED\"]");
        assertThat((String) read(page, "$.entries[1].entityName")).isEqualTo("Beta");
        assertThat((String) read(page, "$.entries[1].userName")).isEqualTo("Test User");
        assertThat(((Number) read(page, "$.entries[2].details.sellingPrice")).doubleValue()).isEqualTo(3.0);
        String deleted = getJson(shini.adminToken(), "/api/guardian/activity?type=PRODUCT_DELETED");
        assertThat((Integer) read(deleted, "$.total")).isEqualTo(2);
        assertThat(page).doesNotContain("Gamma").doesNotContain("password");
        String second = getJson(shini.adminToken(), "/api/guardian/activity?page=1");
        assertThat((List<?>) read(second, "$.entries")).isEmpty();
    }

    @Test
    void nothingInTheRequestCanChooseTheStore() throws Exception {
        long milk = product(shini.adminToken(), "Milk", "11111111", "dairy", 5, 6.0, 0, 5);
        setGuardian(neshan.adminToken(), true);
        // A store id in the query is ignored: Neshan's admin still sees only Neshan.
        String d = getJson(neshan.adminToken(), "/api/guardian/dashboard?storeId=" + shini.storeId());
        assertThat(d).doesNotContain("Milk");
        assertThat(alerts(getJson(neshan.adminToken(), "/api/guardian/alerts?storeId=" + shini.storeId()))).isEmpty();
        assertThat(milk).isPositive();
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("enabled", false);
        mvc.perform(get("/api/guardian/status").header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
