package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U5 – Reports, Sales Analytics and the Guardian 7-day chart at midnight.
 * A sale or return made in the first second after midnight is stored as "2026-11-01T00:00"
 * (no seconds). It must count on ITS day, month and year, in every figure, for its own store only.
 *
 * <p>Shini: Milk ₪10 (cost 6), tax 16 %, maximum discount 10 %.
 * <pre>
 *  A  2026-10-31T23:59:59  milk ×1                gross 11.60                     → October
 *  B  2026-10-31T23:59     milk ×1                gross 11.60                     → October
 *  C  2026-11-01T00:00     milk ×2, 10 % discount 20 − 2 = 18 + tax 2.88 = 20.88  → November (midnight)
 *  D  2026-11-01T00:00:01  milk ×1                gross 11.60                     → November
 *  E  2026-11-15T10:15     milk ×1                gross 11.60                     → November
 *  R  2026-11-01T00:00     return of 1 milk of A  refund 11.60, revenue 10, cost 6 → November (midnight)
 *  F  2026-12-31T23:59:59  milk ×1                gross 11.60                     → December 2026
 *  G  2027-01-01T00:00     milk ×1                gross 11.60                     → January 2027 (midnight)
 *  Neshan: N 2026-11-01T00:00 milk ×5 – never in Shini's reports.
 * </pre>
 */
class ReportMidnightApiTest extends GuardianApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;
    private long milk;

    private static double num(String json, String path) {
        return ((Number) read(json, path)).doubleValue();
    }

    private void at(long saleId, String time) {
        db.update("UPDATE sales SET completed_at = ?, created_at = ?, updated_at = ? WHERE sale_id = ?",
                time, time, time, saleId);
    }

    private long sale(int units, Double discountPercent, String time) throws Exception {
        long id = sell(shini.cashierToken(), Map.of(milk, units), discountPercent == null ? null : "PERCENT",
                discountPercent);
        at(id, time);
        return id;
    }

    private String summary(String token, String from, String to) throws Exception {
        return getJson(token, "/api/reports/summary?from=" + from + "&to=" + to);
    }

    @BeforeEach
    void data() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
        openCashShifts(shini);
        openCashShifts(neshan);
        milk = product(shini.adminToken(), "Milk", "11111111", "dairy", 10, 6.0, 100, 1);
        long a = sell(shini.cashierToken(), Map.of(milk, 1), null, null);
        returnOne(shini.cashierToken(), a, milk); // made now, dated below
        long returnId = db.queryForObject("SELECT MAX(return_id) FROM returns WHERE sale_id = ?", Long.class, a);
        at(a, "2026-10-31T23:59:59");
        db.update("UPDATE returns SET return_date = ? WHERE return_id = ?", "2026-11-01T00:00", returnId);
        sale(1, null, "2026-10-31T23:59");
        sale(2, 10.0, "2026-11-01T00:00");
        sale(1, null, "2026-11-01T00:00:01");
        sale(1, null, "2026-11-15T10:15");
        sale(1, null, "2026-12-31T23:59:59");
        sale(1, null, "2027-01-01T00:00");
        long nMilk = product(neshan.adminToken(), "Milk", "11111111", "dairy", 10, 6.0, 100, 1);
        long n = sell(neshan.cashierToken(), Map.of(nMilk, 5), null, null);
        db.update("UPDATE sales SET completed_at = ? WHERE sale_id = ?", "2026-11-01T00:00", n);
    }

    @Test
    void aMidnightSaleAndReturnCountInTheirOwnMonthInEveryFigure() throws Exception {
        String nov = summary(shini.adminToken(), "2026-11-01", "2026-11-30");
        assertThat((Integer) read(nov, "$.invoiceCount")).isEqualTo(3);          // C, D, E
        assertThat(num(nov, "$.grossSales")).isEqualTo(44.08);                   // 20.88 + 11.60 + 11.60
        assertThat(num(nov, "$.discounts")).isEqualTo(2.0);
        assertThat(num(nov, "$.tax")).isEqualTo(6.08);                           // 2.88 + 1.60 + 1.60
        assertThat(num(nov, "$.refunds")).isEqualTo(11.6);                       // R
        assertThat(num(nov, "$.netSales")).isEqualTo(32.48);
        assertThat((Integer) read(nov, "$.returnCount")).isEqualTo(1);
        assertThat((Integer) read(nov, "$.unitsSold")).isEqualTo(4);
        assertThat((Integer) read(nov, "$.unitsReturned")).isEqualTo(1);
        assertThat((Integer) read(nov, "$.netUnitsSold")).isEqualTo(3);
        // Profit: revenue without tax 18 + 10 + 10 − 10 (R) = 28, cost 12 + 6 + 6 − 6 = 18.
        assertThat((String) read(nov, "$.profit.status")).isEqualTo("COMPLETE");
        assertThat(num(nov, "$.profit.revenueExTax")).isEqualTo(28.0);
        assertThat(num(nov, "$.profit.costOfGoods")).isEqualTo(18.0);
        assertThat(num(nov, "$.profit.amount")).isEqualTo(10.0);

        String oct = summary(shini.adminToken(), "2026-10-01", "2026-10-31");
        assertThat((Integer) read(oct, "$.invoiceCount")).isEqualTo(2);          // A, B (23:59:59 and 23:59)
        assertThat(num(oct, "$.grossSales")).isEqualTo(23.2);
        assertThat(num(oct, "$.refunds")).isZero();                             // R belongs to November
        assertThat(num(oct, "$.netSales")).isEqualTo(23.2);
        assertThat(num(oct, "$.profit.revenueExTax")).isEqualTo(20.0);
        assertThat(num(oct, "$.profit.amount")).isEqualTo(8.0);
    }

    @Test
    void dailyAndMonthlyChartsMatchTheCards() throws Exception {
        String days = getJson(shini.adminToken(), "/api/reports/daily-sales?from=2026-11-01&to=2026-11-30");
        List<Map<String, Object>> list = read(days, "$");
        Map<String, Object> first = list.get(0);
        assertThat(first.get("date")).isEqualTo("2026-11-01");
        assertThat(((Number) first.get("grossSales")).doubleValue()).isEqualTo(32.48);  // C + D
        assertThat(((Number) first.get("refunds")).doubleValue()).isEqualTo(11.6);      // R
        assertThat(((Number) first.get("invoices")).intValue()).isEqualTo(2);
        assertThat(((Number) list.get(14).get("netSales")).doubleValue()).isEqualTo(11.6);  // E on the 15th
        double sum = list.stream().mapToDouble(d -> ((Number) d.get("netSales")).doubleValue()).sum();
        assertThat(Math.round(sum * 100) / 100.0).isEqualTo(32.48);                     // = November card

        String octDays = getJson(shini.adminToken(), "/api/reports/daily-sales?from=2026-10-01&to=2026-10-31");
        List<Map<String, Object>> octList = read(octDays, "$");
        double octSum = octList.stream().mapToDouble(d -> ((Number) d.get("netSales")).doubleValue()).sum();
        assertThat(Math.round(octSum * 100) / 100.0).isEqualTo(23.2);                   // = October card

        String y2026 = getJson(shini.adminToken(), "/api/reports/monthly-sales?year=2026");
        assertThat(num(y2026, "$.months[9].netSales")).isEqualTo(23.2);   // October
        assertThat(num(y2026, "$.months[10].netSales")).isEqualTo(32.48); // November = card
        assertThat(num(y2026, "$.months[11].netSales")).isEqualTo(11.6);  // December: F only
        assertThat(num(y2026, "$.totalNetSales")).isEqualTo(67.28);
        String y2027 = getJson(shini.adminToken(), "/api/reports/monthly-sales?year=2027");
        assertThat(num(y2027, "$.months[0].netSales")).isEqualTo(11.6);   // G, midnight of 1 January
        assertThat((Integer) read(y2027, "$.months[0].invoices")).isEqualTo(1);
        String jan = summary(shini.adminToken(), "2027-01-01", "2027-01-31");
        assertThat((Integer) read(jan, "$.invoiceCount")).isEqualTo(1);
        String dec = summary(shini.adminToken(), "2026-12-01", "2026-12-31");
        assertThat((Integer) read(dec, "$.invoiceCount")).isEqualTo(1);    // F, not G
    }

    @Test
    void thePreviousPeriodAndTheBestSellersUseTheSameDays() throws Exception {
        String dec = summary(shini.adminToken(), "2026-12-01", "2026-12-31");
        assertThat((String) read(dec, "$.previous.from")).isEqualTo("2026-11-01");
        assertThat(num(dec, "$.previous.netSales")).isEqualTo(32.48);
        assertThat((Integer) read(dec, "$.previous.invoiceCount")).isEqualTo(3);
        assertThat((Integer) read(dec, "$.previous.netUnitsSold")).isEqualTo(3);
        assertThat(num(dec, "$.previous.profit")).isEqualTo(10.0);

        String top = getJson(shini.adminToken(), "/api/reports/top-products?from=2026-11-01&to=2026-11-30");
        assertThat((String) read(top, "$.products[0].name")).isEqualTo("Milk");
        assertThat((Integer) read(top, "$.products[0].units")).isEqualTo(3);   // 4 sold − 1 returned
        assertThat(num(top, "$.products[0].salesValue")).isEqualTo(32.48);
        assertThat(num(top, "$.totalSalesValue")).isEqualTo(32.48);           // = November card
        String topOct = getJson(shini.adminToken(), "/api/reports/top-products?from=2026-10-01&to=2026-10-31");
        assertThat((Integer) read(topOct, "$.products[0].units")).isEqualTo(2);
    }

    @Test
    void eachStoreSeesOnlyItsOwnMidnightSales() throws Exception {
        String nNov = summary(neshan.adminToken(), "2026-11-01", "2026-11-30");
        assertThat((Integer) read(nNov, "$.invoiceCount")).isEqualTo(1);
        assertThat((Integer) read(nNov, "$.unitsSold")).isEqualTo(5);
        assertThat((Integer) read(summary(neshan.adminToken(), "2026-10-01", "2026-10-31"), "$.invoiceCount")).isZero();
        // A store id in the URL is ignored: always the logged-in user's store.
        String shiniWithOtherId = getJson(shini.adminToken(),
                "/api/reports/summary?from=2026-11-01&to=2026-11-30&storeId=" + neshan.storeId());
        assertThat((Integer) read(shiniWithOtherId, "$.invoiceCount")).isEqualTo(3);
        assertThat((Integer) read(shiniWithOtherId, "$.unitsSold")).isEqualTo(4);
    }

    @Test
    void dateValidationIsUnchanged() throws Exception {
        mvc.perform(get("/api/reports/summary?from=2026-11-30&to=2026-11-01")
                .header("Authorization", bearer(shini.adminToken()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/daily-sales?from=2025-01-01&to=2026-12-31")
                .header("Authorization", bearer(shini.adminToken()))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/monthly-sales?year=1999")
                .header("Authorization", bearer(shini.adminToken()))).andExpect(status().isBadRequest());
    }

    /** Guardian's 7-day chart comes from Sales Analytics: midnight sales on its first and last day count there. */
    @Test
    void theGuardianSevenDayChartMatchesSalesAnalyticsAtMidnight() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate first = today.minusDays(6);
        sale(1, null, first + "T00:00");   // 11.60, first second of the chart's first day
        sale(2, null, today + "T00:00");   // 23.20, first second of today
        setGuardian(shini.adminToken(), true);
        String d = getJson(shini.adminToken(), "/api/guardian/dashboard");
        List<Number> week = read(d, "$.todaySales.last7Days[*].netSales");
        assertThat(week.get(0).doubleValue()).isEqualTo(11.6);
        assertThat(week.get(6).doubleValue()).isEqualTo(23.2);
        assertThat(num(d, "$.todaySales.today")).isEqualTo(23.2);   // Guardian's own "today" figure agrees
        String days = getJson(shini.adminToken(), "/api/reports/daily-sales?from=" + first + "&to=" + today);
        List<Number> analytics = read(days, "$[*].netSales");
        for (int i = 0; i < 7; i++) assertThat(week.get(i).doubleValue()).isEqualTo(analytics.get(i).doubleValue());
    }
}
