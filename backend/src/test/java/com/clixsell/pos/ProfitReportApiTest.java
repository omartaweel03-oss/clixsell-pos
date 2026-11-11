package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profit (Figure 3.8) with the sale-time cost, discount, tax and returns,
 * against the real database.
 *
 * <p>Shini – Milk ₪5 (cost 3.00), Bread ₪2 (cost 1.20), Juice ₪8 (no cost at first). Tax 16 %.
 * <pre>
 *  05 Mar  S1 milk ×2 + bread ×1              revenue ex-tax 12.00, cost 7.20  → profit 4.80
 *  20 Mar  S2 milk ×4, 10 % discount          revenue 20 − 2 = 18.00, cost 12  → profit 6.00
 *  25 Mar  return 1 milk of S2 (partial)      revenue 18 × 1/4 = 4.50, cost 3  → −1.50
 *  ── March: profit 9.30, revenue ex-tax 25.50, net revenue (with tax) 34.80 − 5.22 = 29.58
 *  02 Apr  S3 bread ×1                        revenue 2.00, cost 1.20          → profit 0.80
 *  03 Apr  return 1 milk of S1 (cross-month)  revenue 12 × 10/12 × 1/2 = 5.00, cost 3 → −2.00
 *  ── April: profit −1.20 (the March sale is not changed)
 *  (milk cost is then changed to 4.00: past profit must not change)
 *  10 May  S4 juice ×1 (no cost) + milk ×1    milk: revenue 5, cost 4 → 1.00; juice unknown
 *  12 May  S5 juice ×1 (cost now 6.00)        revenue 8, cost 6        → 2.00
 *  ── May: PARTIAL profit 3.00 for 13 of 21 revenue (61.90 %), never shown as complete
 * </pre>
 */
class ProfitReportApiTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;
    private long milk;
    private long bread;
    private long juice;

    private long product(String token, String name, String barcode, double price, int qty, Double cost)
            throws Exception {
        MvcResult r = mvc.perform(post("/api/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(name, barcode, price, qty, cost)))
                .andExpect(status().isCreated()).andReturn();
        return jsonLong(r, "productId");
    }

    private static String json(String name, String barcode, double price, int qty, Double cost) {
        return """
                {"name":"%s","barcode":"%s","category":"dairy","sellingPrice":%s,"quantity":%d,"minStock":1,
                 "supplierName":"Main Supplier","costPrice":%s}""".formatted(name, barcode, price, qty, cost);
    }

    private String sell(String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/sales").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return jsonString(r, "invoiceNumber");
    }

    private long saleId(String invoice) {
        return db.queryForObject("SELECT sale_id FROM sales WHERE store_id = ? AND invoice_number = ?",
                Long.class, shini.storeId(), invoice);
    }

    private void returnOne(String invoice, long productId, String date) throws Exception {
        long sale = saleId(invoice);
        long item = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ? AND product_id = ?",
                Long.class, sale, productId);
        MvcResult r = mvc.perform(post("/api/returns").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":1,\"reason\":\"Damaged\"}"
                                .formatted(sale, item)))
                .andExpect(status().isCreated()).andReturn();
        db.update("UPDATE returns SET return_date = ? WHERE return_id = ?", date, jsonLong(r, "returnId"));
    }

    private void date(String invoice, String at) {
        db.update("UPDATE sales SET completed_at = ?, created_at = ? WHERE store_id = ? AND invoice_number = ?",
                at, at, shini.storeId(), invoice);
    }

    @BeforeEach
    void history() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
        milk = product(shini.adminToken(), "Milk", "11111111", 5.00, 20, 3.00);
        bread = product(shini.adminToken(), "Bread", "22222222", 2.00, 10, 1.20);
        juice = product(shini.adminToken(), "Juice", "33333333", 8.00, 10, null);

        String s1 = sell("{\"items\":[{\"productId\":%d,\"quantity\":2},{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(milk, bread));
        String s2 = sell("{\"items\":[{\"productId\":%d,\"quantity\":4}],\"discountType\":\"PERCENT\",\"discountValue\":10,\"paymentMethod\":\"CARD\"}"
                .formatted(milk));
        String s3 = sell("{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}".formatted(bread));
        // Returns are limited to 14 days, so they are made now and dated afterwards.
        returnOne(s2, milk, "2026-03-25T12:00:00");
        returnOne(s1, milk, "2026-04-03T12:00:00");

        // The milk cost changes after those sales: their profit must not change.
        mvc.perform(put("/api/products/" + milk).header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("Milk", "11111111", 5.00, 15, 4.00)))
                .andExpect(status().isOk());
        String s4 = sell("{\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(juice, milk));
        mvc.perform(put("/api/products/" + juice).header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("Juice", "33333333", 8.00, 9, 6.00)))
                .andExpect(status().isOk());
        String s5 = sell("{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}".formatted(juice));

        date(s1, "2026-03-05T10:00:00");
        date(s2, "2026-03-20T18:30:00");
        date(s3, "2026-04-02T09:15:00");
        date(s4, "2026-05-10T10:00:00");
        date(s5, "2026-05-12T10:00:00");
    }

    private org.springframework.test.web.servlet.ResultActions summary(String token, String from, String to)
            throws Exception {
        return mvc.perform(get("/api/reports/summary?from=" + from + "&to=" + to)
                .header("Authorization", bearer(token)));
    }

    @Test
    void saleTimeCostIsSavedOnEachSoldLine() {
        // S1/S2 were sold at cost 3.00; the later change to 4.00 only affects later sales.
        assertCosts("SELECT unit_cost FROM sale_items WHERE product_id = " + milk + " ORDER BY sale_item_id",
                3.00, 3.00, 4.00);
        assertCosts("SELECT unit_cost FROM sale_items WHERE product_id = " + juice + " ORDER BY sale_item_id",
                null, 6.00);
    }

    private void assertCosts(String sql, Double... expected) {
        org.assertj.core.api.Assertions.assertThat(db.queryForList(sql, Double.class)).containsExactly(expected);
    }

    @Test
    void profitUsesRevenueWithoutTaxAfterDiscountMinusCostAndPartialReturns() throws Exception {
        summary(shini.adminToken(), "2026-03-01", "2026-03-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profit.status").value("COMPLETE"))
                .andExpect(jsonPath("$.profit.complete").value(true))
                .andExpect(jsonPath("$.profit.amount").value(9.30))
                .andExpect(jsonPath("$.profit.revenueExTax").value(25.50))
                .andExpect(jsonPath("$.profit.costOfGoods").value(16.20))
                .andExpect(jsonPath("$.profit.coveragePercent").value(100.0))
                // Net revenue keeps the tax: 34.80 − 5.22. Discount and tax are not counted twice:
                // revenue ex-tax 25.50 + tax 4.80 − tax refunded 0.72 = 29.58.
                .andExpect(jsonPath("$.netSales").value(29.58))
                .andExpect(jsonPath("$.refunds").value(5.22))
                .andExpect(jsonPath("$.tax").value(4.80))
                .andExpect(jsonPath("$.unitsSold").value(7))
                .andExpect(jsonPath("$.unitsReturned").value(1))
                .andExpect(jsonPath("$.netUnitsSold").value(6))
                .andExpect(jsonPath("$.invoiceCount").value(2));
    }

    @Test
    void crossMonthReturnIsSubtractedOnItsOwnDate() throws Exception {
        summary(shini.adminToken(), "2026-04-01", "2026-04-30")
                .andExpect(jsonPath("$.profit.status").value("COMPLETE"))
                .andExpect(jsonPath("$.profit.amount").value(-1.20))   // 0.80 − 2.00
                .andExpect(jsonPath("$.profit.revenueExTax").value(-3.00))
                .andExpect(jsonPath("$.netSales").value(-3.48))        // 2.32 − 5.80
                .andExpect(jsonPath("$.netUnitsSold").value(0))        // 1 sold − 1 returned
                .andExpect(jsonPath("$.previous.profit").value(9.30))  // March is not changed by the April return
                .andExpect(jsonPath("$.previous.netUnitsSold").value(6));
        // Best-selling products follow the same dates: totals equal Net Revenue / Net Units,
        // the milk returned in April (sold in March) is not listed as an April best seller.
        mvc.perform(get("/api/reports/top-products?from=2026-04-01&to=2026-04-30")
                        .header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.totalSalesValue").value(-3.48))
                .andExpect(jsonPath("$.totalUnits").value(0))
                .andExpect(jsonPath("$.products", hasSize(1)))
                .andExpect(jsonPath("$.products[0].name").value("Bread"))
                .andExpect(jsonPath("$.products[0].salesValue").value(2.32));
        // March: milk 6 sold − 1 returned in March = 5 units, 32.48 − 5.22 = 27.26
        mvc.perform(get("/api/reports/top-products?from=2026-03-01&to=2026-03-31")
                        .header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.products[0].name").value("Milk"))
                .andExpect(jsonPath("$.products[0].units").value(5))
                .andExpect(jsonPath("$.products[0].salesValue").value(27.26))
                .andExpect(jsonPath("$.totalSalesValue").value(29.58));
    }

    @Test
    void historicalSalesWithoutCostGiveAPartialProfitNeverAComplete() throws Exception {
        summary(shini.adminToken(), "2026-05-01", "2026-05-31")
                .andExpect(jsonPath("$.profit.status").value("PARTIAL"))
                .andExpect(jsonPath("$.profit.complete").value(false))
                .andExpect(jsonPath("$.profit.amount").value(3.00))            // milk 1.00 + juice 2.00
                .andExpect(jsonPath("$.profit.revenueExTax").value(21.00))     // 13 + 8
                .andExpect(jsonPath("$.profit.coveredRevenueExTax").value(13.00))
                .andExpect(jsonPath("$.profit.coveragePercent").value(61.90))
                .andExpect(jsonPath("$.profit.linesWithoutCost").value(1))
                .andExpect(jsonPath("$.profit.reason").value("MISSING_COST"));
        // A month without sales: the profit is exactly 0, and May (partial) is not used for comparison.
        summary(shini.adminToken(), "2026-06-01", "2026-06-30")
                .andExpect(jsonPath("$.profit.status").value("COMPLETE"))
                .andExpect(jsonPath("$.profit.amount").value(0.0))
                .andExpect(jsonPath("$.previous.profit").value(nullValue()));
    }

    @Test
    void storeWithoutAnyCostHasNoProfitAndStoresStaySeparate() throws Exception {
        long water = product(neshan.adminToken(), "Water", "44444444", 1.00, 10, null);
        mvc.perform(post("/api/sales").header("Authorization", bearer(neshan.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":3}],\"paymentMethod\":\"CASH\"}".formatted(water)))
                .andExpect(status().isCreated());
        db.update("UPDATE sales SET completed_at = '2026-03-10T10:00:00' WHERE store_id = ?", neshan.storeId());
        summary(neshan.adminToken(), "2026-03-01", "2026-03-31")
                .andExpect(jsonPath("$.profit.status").value("NOT_AVAILABLE"))
                .andExpect(jsonPath("$.profit.available").value(false))
                .andExpect(jsonPath("$.profit.amount").value(nullValue()))
                .andExpect(jsonPath("$.profit.reason").value("NO_COST_PRICE"))
                .andExpect(jsonPath("$.netSales").value(3.48));
        // Shini's March profit is unaffected by Neshan.
        summary(shini.adminToken(), "2026-03-01", "2026-03-31")
                .andExpect(jsonPath("$.profit.amount").value(9.30));
    }

    @Test
    void onlyStoreAdminsSeeAndSetPurchaseCosts() throws Exception {
        mvc.perform(get("/api/products/" + milk).header("Authorization", bearer(shini.adminToken())))
                .andExpect(jsonPath("$.costPrice").value(4.00));
        mvc.perform(get("/api/products").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].costPrice").value(nullValue()))
                .andExpect(jsonPath("$[1].costPrice").value(nullValue()))
                .andExpect(jsonPath("$[2].costPrice").value(nullValue()));
        mvc.perform(get("/api/products/" + milk).header("Authorization", bearer(shini.cashierToken())))
                .andExpect(jsonPath("$.costPrice").value(nullValue()));
        // The other store's admin cannot read it either.
        mvc.perform(get("/api/products/" + milk).header("Authorization", bearer(neshan.adminToken())))
                .andExpect(status().isNotFound());
        // Cashiers cannot change products (or costs); profit reports are for Store Admins.
        mvc.perform(put("/api/products/" + milk).header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("Milk", "11111111", 5.00, 15, 0.10)))
                .andExpect(status().isForbidden());
        summary(shini.cashierToken(), "2026-03-01", "2026-03-31").andExpect(status().isForbidden());
    }

    @Test
    void invalidCostPricesAreRefused() throws Exception {
        for (String bad : new String[]{"-1", "1.234"}) {
            mvc.perform(post("/api/products").header("Authorization", bearer(shini.adminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("Tea", "55555555", 3.00, 5, null).replace("\"costPrice\":null",
                                    "\"costPrice\":" + bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.costPrice").exists());
        }
        // Empty cost is allowed: the cost is simply not known.
        product(shini.adminToken(), "Tea", "55555555", 3.00, 5, null);
    }
}
