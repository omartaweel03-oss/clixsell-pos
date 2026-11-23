package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the defects confirmed by the Guardian Mode final audit.
 */
class GuardianAuditApiTest extends GuardianApiTestBase {

    private StoreSetup shini;

    @BeforeEach
    void store() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
    }

    /**
     * Dates are stored with LocalDateTime.toString(), which leaves out ":00"
     * seconds: a sale at exactly midnight is "2026-12-01T00:00". It must count
     * in ITS day / month, not the one before.
     */
    @Test
    void aSaleAtExactlyMidnightBelongsToItsOwnDayAndMonth() throws Exception {
        long milk = product(shini.adminToken(), "Milk", "11111111", "dairy", 5, 3.2, 40, 5);
        long sale = sell(shini.cashierToken(), Map.of(milk, 2), null, null);
        LocalDate first = YearMonth.now().atDay(1);
        db.update("UPDATE sales SET completed_at = ?, created_at = ?, updated_at = ? WHERE sale_id = ?",
                first + "T00:00", first + "T00:00", first + "T00:00", sale);
        setGuardian(shini.adminToken(), true);

        String now = getJson(shini.adminToken(), "/api/guardian/dashboard");
        assertThat((Integer) read(now, "$.costData.invoices")).isEqualTo(1);
        YearMonth before = YearMonth.now().minusMonths(1);
        String last = getJson(shini.adminToken(),
                "/api/guardian/dashboard?year=" + before.getYear() + "&month=" + before.getMonthValue());
        assertThat((Integer) read(last, "$.costData.invoices")).isZero();

        // Today's sales: a sale stored as "<today>T00:00" is today's.
        db.update("UPDATE sales SET completed_at = ? WHERE sale_id = ?", LocalDate.now() + "T00:00", sale);
        double total = db.queryForObject("SELECT total FROM sales WHERE sale_id = ?", Double.class, sale);
        assertThat(((Number) read(getJson(shini.adminToken(), "/api/guardian/dashboard"), "$.todaySales.today"))
                .doubleValue()).isEqualTo(total);

        // The activity report too.
        long admin = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.admin'", Long.class);
        db.update("INSERT INTO activity_log (store_id, user_id, event_type, entity_type, entity_id, entity_name, created_at) "
                + "VALUES (?, ?, 'PRODUCT_DELETED', 'PRODUCT', 99, 'Old', ?)", shini.storeId(), admin, first + "T00:00");
        String page = getJson(shini.adminToken(), "/api/guardian/activity?type=PRODUCT_DELETED");
        assertThat((Integer) read(page, "$.total")).isEqualTo(1);
    }

    /**
     * A loss at the time of sale stays a sale-time loss after the goods come
     * back, and the alert says how many units were returned (the app must not
     * present it as a loss that still exists).
     */
    @Test
    void aReturnedLossKeepsItsSaleTimeNumbersAndSaysWhatWasReturned() throws Exception {
        long cake = product(shini.adminToken(), "Promo Cake", "33333333", "bakery", 2, 3.0, 30, 2);
        long sale = sell(shini.cashierToken(), Map.of(cake, 5), null, null);
        for (int i = 0; i < 5; i++) returnOne(shini.cashierToken(), sale, cake);
        setGuardian(shini.adminToken(), true);

        String all = getJson(shini.adminToken(), "/api/guardian/alerts?rule=G2b");
        List<Map<String, Object>> alerts = alerts(all);
        assertThat(alerts).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) alerts.get(0).get("params");
        assertThat(((Number) p.get("loss")).doubleValue()).isEqualTo(5.0);
        assertThat(p.get("unitsSold")).isEqualTo(5);
        assertThat(p.get("unitsReturned")).isEqualTo(5);
        String lines = getJson(shini.adminToken(), "/api/guardian/alerts?rule=G2a");
        assertThat(read(lines, "$.alerts[0].params.unitsReturned").toString()).isEqualTo("5");
    }
}
