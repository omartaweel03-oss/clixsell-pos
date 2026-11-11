package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reports and Sales Analytics API (Figures 3.7 / 3.8, UR-M7/M8, SR20/SR21)
 * against the real database, with two stores.
 *
 * <p>Shini, March 2026:
 * <pre>
 *  05 Mar  INV-1  milk ×2 (5.00) + bread ×1 (2.00), cash      subtotal 12.00  tax 1.92  total 13.92
 *  20 Mar  INV-2  milk ×4, 10 % discount, card                 subtotal 20.00  −2.00  tax 2.88  total 20.88
 *  21 Mar  return 1 milk of INV-1: refund 13.92 × 10/12 × 1/2 = 5.80
 *  02 Apr  INV-3  bread ×1                                    total 2.32
 *  + one suspended and one canceled invoice (never counted)
 * </pre>
 * Neshan, 10 Mar: juice ×1 (8.00) total 9.28.
 */
class ReportApiIntegrationTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;
    private long milk;   // 5.00, stock 10, min 2
    private long bread;  // 2.00, stock 3,  min 2
    private long eggs;   // 15.00, stock 0

    private static final String MARCH = "from=2026-03-01&to=2026-03-31";

    private String sell(String token, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/sales").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return jsonString(r, "invoiceNumber");
    }

    private void dateSale(long storeId, String invoice, String at) {
        db.update("UPDATE sales SET completed_at = ?, created_at = ? WHERE store_id = ? AND invoice_number = ?",
                at, at, storeId, invoice);
    }

    @BeforeEach
    void salesHistory() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
        milk = createProduct(shini.adminToken(), "Milk", "11111111", 5.00, 10);
        bread = createProduct(shini.adminToken(), "Bread", "22222222", 2.00, 3);
        eggs = createProduct(shini.adminToken(), "Eggs", "33333333", 15.00, 0);
        long juice = createProduct(neshan.adminToken(), "Juice", "44444444", 8.00, 5);

        String inv1 = sell(shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":2},{"productId":%d,"quantity":1}],"paymentMethod":"CASH"}"""
                .formatted(milk, bread));
        String inv2 = sell(shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":4}],"discountType":"PERCENT","discountValue":10,
                 "paymentMethod":"CARD"}""".formatted(milk));
        String inv3 = sell(shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":1}],"paymentMethod":"CASH"}""".formatted(bread));

        // Suspended and canceled invoices must never be counted.
        mvc.perform(post("/api/sales/suspended").header("Authorization", bearer(shini.cashierToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(milk)));
        MvcResult toCancel = mvc.perform(post("/api/sales/suspended")
                        .header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":3}]}".formatted(milk)))
                .andReturn();
        mvc.perform(delete("/api/sales/suspended/" + jsonLong(toCancel, "saleId"))
                .header("Authorization", bearer(shini.cashierToken())));
        db.update("UPDATE sales SET created_at = '2026-03-15T12:00:00', updated_at = '2026-03-15T12:00:00' "
                + "WHERE status <> 'COMPLETED'");

        // Return 1 milk of INV-1 (dated 21 March below).
        long saleId = db.queryForObject("SELECT sale_id FROM sales WHERE store_id = ? AND invoice_number = ?",
                Long.class, shini.storeId(), inv1);
        long itemId = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ? AND product_id = ?",
                Long.class, saleId, milk);
        mvc.perform(post("/api/returns").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":1,\"reason\":\"Damaged\"}"
                                .formatted(saleId, itemId)))
                .andExpect(status().isCreated());
        // Move the history into March / April 2026 (after the return: returns are limited to 14 days).
        dateSale(shini.storeId(), inv1, "2026-03-05T10:00:00");
        dateSale(shini.storeId(), inv2, "2026-03-20T18:30:00");
        dateSale(shini.storeId(), inv3, "2026-04-02T09:15:00");
        db.update("UPDATE returns SET return_date = '2026-03-21T09:00:00'");

        String inv4 = sell(neshan.cashierToken(), """
                {"items":[{"productId":%d,"quantity":1}],"paymentMethod":"CASH"}""".formatted(juice));
        dateSale(neshan.storeId(), inv4, "2026-03-10T11:00:00");
    }

    @Test
    void summaryCountsOnlyCompletedSalesAndSubtractsRefunds() throws Exception {
        mvc.perform(get("/api/reports/summary?" + MARCH).header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-03-01"))
                .andExpect(jsonPath("$.to").value("2026-03-31"))
                .andExpect(jsonPath("$.grossSales").value(34.80))
                .andExpect(jsonPath("$.discounts").value(2.00))
                .andExpect(jsonPath("$.tax").value(4.80))
                .andExpect(jsonPath("$.refunds").value(5.80))
                .andExpect(jsonPath("$.netSales").value(29.00))
                .andExpect(jsonPath("$.invoiceCount").value(2))
                .andExpect(jsonPath("$.returnCount").value(1))
                .andExpect(jsonPath("$.unitsSold").value(7))
                .andExpect(jsonPath("$.unitsReturned").value(1))
                .andExpect(jsonPath("$.averageInvoice").value(17.40))
                .andExpect(jsonPath("$.previous.from").value("2026-02-01"))
                .andExpect(jsonPath("$.previous.to").value("2026-02-28"))
                .andExpect(jsonPath("$.previous.netSales").value(0.0))
                .andExpect(jsonPath("$.previous.invoiceCount").value(0))
                // These products have no purchase cost, so profit is never estimated.
                .andExpect(jsonPath("$.netUnitsSold").value(6))
                .andExpect(jsonPath("$.profit.status").value("NOT_AVAILABLE"))
                .andExpect(jsonPath("$.profit.available").value(false))
                .andExpect(jsonPath("$.profit.amount").doesNotExist())
                .andExpect(jsonPath("$.profit.reason").value("NO_COST_PRICE"));
    }

    @Test
    void summaryComparesWithThePreviousMonth() throws Exception {
        mvc.perform(get("/api/reports/summary?from=2026-04-01&to=2026-04-30")
                        .header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.netSales").value(2.32))
                .andExpect(jsonPath("$.invoiceCount").value(1))
                .andExpect(jsonPath("$.refunds").value(0.0))
                .andExpect(jsonPath("$.previous.from").value("2026-03-01"))
                .andExpect(jsonPath("$.previous.netSales").value(29.00))
                .andExpect(jsonPath("$.previous.invoiceCount").value(2));
    }

    @Test
    void dailySalesHasEveryDayOfThePeriodWithZerosAndRefundsOnTheirDay() throws Exception {
        mvc.perform(get("/api/reports/daily-sales?" + MARCH).header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(31)))
                .andExpect(jsonPath("$[0].date").value("2026-03-01"))
                .andExpect(jsonPath("$[0].netSales").value(0.0))
                .andExpect(jsonPath("$[4].date").value("2026-03-05"))
                .andExpect(jsonPath("$[4].grossSales").value(13.92))
                .andExpect(jsonPath("$[4].invoices").value(1))
                .andExpect(jsonPath("$[14].netSales").value(0.0))   // 15 Mar: only suspended / canceled
                .andExpect(jsonPath("$[19].netSales").value(20.88))
                .andExpect(jsonPath("$[20].refunds").value(5.80))
                .andExpect(jsonPath("$[20].netSales").value(-5.80))
                .andExpect(jsonPath("$[30].date").value("2026-03-31"));
    }

    @Test
    void wholeYearComparesWithThePreviousYear() throws Exception {
        mvc.perform(get("/api/reports/summary?from=2024-01-01&to=2024-12-31")   // leap year
                        .header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.previous.from").value("2023-01-01"))
                .andExpect(jsonPath("$.previous.to").value("2023-12-31"));
        mvc.perform(get("/api/reports/summary?from=2026-01-01&to=2026-12-31")
                        .header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.netSales").value(31.32))
                .andExpect(jsonPath("$.invoiceCount").value(3));
    }

    @Test
    void dayBoundariesAreWholeDays() throws Exception {
        mvc.perform(get("/api/reports/summary?from=2026-03-20&to=2026-03-20")
                        .header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.grossSales").value(20.88))
                .andExpect(jsonPath("$.invoiceCount").value(1))
                .andExpect(jsonPath("$.refunds").value(0.0))
                // a 1-day period compares with the day before
                .andExpect(jsonPath("$.previous.from").value("2026-03-19"))
                .andExpect(jsonPath("$.previous.to").value("2026-03-19"));
    }

    @Test
    void monthlySalesHasTwelveMonths() throws Exception {
        mvc.perform(get("/api/reports/monthly-sales?year=2026").header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.months", hasSize(12)))
                .andExpect(jsonPath("$.months[0].netSales").value(0.0))
                .andExpect(jsonPath("$.months[2].month").value(3))
                .andExpect(jsonPath("$.months[2].grossSales").value(34.80))
                .andExpect(jsonPath("$.months[2].refunds").value(5.80))
                .andExpect(jsonPath("$.months[2].netSales").value(29.00))
                .andExpect(jsonPath("$.months[2].invoices").value(2))
                .andExpect(jsonPath("$.months[3].netSales").value(2.32))
                .andExpect(jsonPath("$.totalNetSales").value(31.32));
        mvc.perform(get("/api/reports/monthly-sales?year=2025").header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.totalNetSales").value(0.0));
    }

    @Test
    void bestSellingProductsUseUnitsKeptAndThePaidShare() throws Exception {
        // milk: INV-1 2−1 returned = 1 unit (13.92 × 10/12 × 1/2 = 5.80) + INV-2 4 units (20.88) = 5 units, 26.68
        // bread: INV-1 1 unit = 13.92 × 2/12 = 2.32
        mvc.perform(get("/api/reports/top-products?" + MARCH).header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSalesValue").value(29.00))
                .andExpect(jsonPath("$.totalUnits").value(6))
                .andExpect(jsonPath("$.products", hasSize(2)))
                .andExpect(jsonPath("$.products[0].name").value("Milk"))
                .andExpect(jsonPath("$.products[0].productId").value(milk))
                .andExpect(jsonPath("$.products[0].units").value(5))
                .andExpect(jsonPath("$.products[0].salesValue").value(26.68))
                .andExpect(jsonPath("$.products[0].share").value(92.0))
                .andExpect(jsonPath("$.products[1].name").value("Bread"))
                .andExpect(jsonPath("$.products[1].salesValue").value(2.32))
                .andExpect(jsonPath("$.othersValue").value(0.0));
        mvc.perform(get("/api/reports/top-products?" + MARCH + "&limit=1")
                        .header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.products", hasSize(1)))
                .andExpect(jsonPath("$.othersValue").value(2.32))
                .andExpect(jsonPath("$.othersUnits").value(1));
    }

    @Test
    void deletedProductKeepsItsSalesUnderTheInvoiceName() throws Exception {
        mvc.perform(delete("/api/products/" + bread).header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/reports/top-products?" + MARCH).header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.products[1].name").value("Bread"))
                .andExpect(jsonPath("$.products[1].productId").doesNotExist())
                .andExpect(jsonPath("$.totalSalesValue").value(29.00));
    }

    @Test
    void inventoryReportUsesTheExistingStockRules() throws Exception {
        // milk 10 − 2 − 4 + 1 returned = 5 (suspended invoices reserve nothing); bread 3 − 2 = 1 ≤ min 2; eggs 0
        mvc.perform(get("/api/reports/inventory").header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(3))
                .andExpect(jsonPath("$.totalUnits").value(6))
                .andExpect(jsonPath("$.inventoryValue").value(27.00))
                .andExpect(jsonPath("$.inStock").value(1))
                .andExpect(jsonPath("$.lowStock").value(1))
                .andExpect(jsonPath("$.outOfStock").value(1))
                .andExpect(jsonPath("$.categories", hasSize(1)))
                .andExpect(jsonPath("$.categories[0].category").value("dairy"))
                .andExpect(jsonPath("$.categories[0].products").value(3))
                .andExpect(jsonPath("$.reorder", hasSize(2)))
                .andExpect(jsonPath("$.reorder[0].productId").value(eggs))
                .andExpect(jsonPath("$.reorder[0].stockStatus").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.reorder[1].name").value("Bread"))
                .andExpect(jsonPath("$.reorder[1].quantity").value(1))
                .andExpect(jsonPath("$.reorder[1].minStock").value(2))
                .andExpect(jsonPath("$.reorder[1].stockStatus").value("LOW_STOCK"))
                .andExpect(jsonPath("$.reorder[1].supplierName").value("Main Supplier"));
    }

    @Test
    void eachStoreSeesOnlyItsOwnReports() throws Exception {
        mvc.perform(get("/api/reports/summary?" + MARCH).header("Authorization", bearer(neshan.adminToken())))
                .andExpect(jsonPath("$.grossSales").value(9.28))
                .andExpect(jsonPath("$.invoiceCount").value(1))
                .andExpect(jsonPath("$.refunds").value(0.0));
        mvc.perform(get("/api/reports/top-products?" + MARCH).header("Authorization", bearer(neshan.adminToken())))
                .andExpect(jsonPath("$.products", hasSize(1)))
                .andExpect(jsonPath("$.products[0].name").value("Juice"));
        mvc.perform(get("/api/reports/inventory").header("Authorization", bearer(neshan.adminToken())))
                .andExpect(jsonPath("$.totalProducts").value(1))
                .andExpect(jsonPath("$.reorder", hasSize(0)));
        mvc.perform(get("/api/reports/monthly-sales?year=2026").header("Authorization", bearer(neshan.adminToken())))
                .andExpect(jsonPath("$.months[3].netSales").value(0.0))
                .andExpect(jsonPath("$.totalNetSales").value(9.28));
        // A store id in the request is ignored: the store always comes from the session.
        mvc.perform(get("/api/reports/summary?" + MARCH + "&storeId=" + shini.storeId())
                        .header("Authorization", bearer(neshan.adminToken())))
                .andExpect(jsonPath("$.grossSales").value(9.28));
    }

    @Test
    void reportsAreForStoreAdminsOnly() throws Exception {
        for (String url : new String[]{"/api/reports/summary?" + MARCH, "/api/reports/daily-sales?" + MARCH,
                "/api/reports/monthly-sales?year=2026", "/api/reports/top-products?" + MARCH,
                "/api/reports/inventory"}) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).header("Authorization", bearer(shini.cashierToken())))
                    .andExpect(status().isForbidden());
            mvc.perform(get(url).header("Authorization", bearer(sysadminToken())))
                    .andExpect(status().isForbidden());
            mvc.perform(get(url).header("Authorization", bearer(shini.adminToken())))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void invalidPeriodsAreRefused() throws Exception {
        String t = bearer(shini.adminToken());
        mvc.perform(get("/api/reports/summary").header("Authorization", t))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.from").exists());
        mvc.perform(get("/api/reports/summary?from=2026-03-31&to=2026-03-01").header("Authorization", t))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").exists());
        mvc.perform(get("/api/reports/daily-sales?from=2024-01-01&to=2026-01-01").header("Authorization", t))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/summary?from=03-2026&to=2026-03-31").header("Authorization", t))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/monthly-sales?year=1999").header("Authorization", t))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/monthly-sales").header("Authorization", t))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reports/top-products?" + MARCH + "&limit=0").header("Authorization", t))
                .andExpect(status().isBadRequest());
    }

    @Test
    void storeWithoutSalesGetsZerosNotErrors() throws Exception {
        StoreSetup empty = setupStore("Empty Shop", "empty");
        String t = bearer(empty.adminToken());
        mvc.perform(get("/api/reports/summary?" + MARCH).header("Authorization", t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netSales").value(0.0))
                .andExpect(jsonPath("$.invoiceCount").value(0))
                .andExpect(jsonPath("$.averageInvoice").value(0.0))
                // nothing sold: the profit is exactly 0
                .andExpect(jsonPath("$.profit.status").value("COMPLETE"))
                .andExpect(jsonPath("$.profit.amount").value(0.0));
        mvc.perform(get("/api/reports/daily-sales?" + MARCH).header("Authorization", t))
                .andExpect(jsonPath("$", hasSize(31)))
                .andExpect(jsonPath("$[10].netSales").value(0.0));
        mvc.perform(get("/api/reports/top-products?" + MARCH).header("Authorization", t))
                .andExpect(jsonPath("$.products", hasSize(0)))
                .andExpect(jsonPath("$.totalSalesValue").value(0.0));
        mvc.perform(get("/api/reports/inventory").header("Authorization", t))
                .andExpect(jsonPath("$.totalProducts").value(0))
                .andExpect(jsonPath("$.inventoryValue").value(0.0))
                .andExpect(jsonPath("$.categories", hasSize(0)));
    }
}
