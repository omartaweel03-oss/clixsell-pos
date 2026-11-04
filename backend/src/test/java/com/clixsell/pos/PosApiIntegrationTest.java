package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cashier Dashboard API with two stores, Shini and Neshan: sales, suspended
 * invoices and returns. After each operation the database is read directly
 * to prove what was saved and how the stock changed.
 */
class PosApiIntegrationTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;
    private long shiniMilk;   // 5.00, stock 10
    private long shiniBread;  // 2.00, stock 3
    private long neshanJuice; // 8.00, stock 5

    @BeforeEach
    void twoStoresWithProducts() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Supermarket", "neshan");
        shiniMilk = createProduct(shini.adminToken(), "Milk 1L", "11111111", 5, 10);
        shiniBread = createProduct(shini.adminToken(), "Bread", "22222222", 2, 3);
        neshanJuice = createProduct(neshan.adminToken(), "Juice", "11111111", 8, 5);
    }

    private org.springframework.test.web.servlet.ResultActions sell(String token, String body) throws Exception {
        return mvc.perform(post("/api/sales").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int stock(long productId) {
        return db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, productId);
    }

    private int count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // ── Complete sale ─────────────────────────────────────────────────────

    @Test
    void completedSaleSavesEverythingAndReducesStock() throws Exception {
        sell(shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":2},{"productId":%d,"quantity":3}],
                 "discountType":"AMOUNT","discountValue":1,"paymentMethod":"CARD"}"""
                .formatted(shiniMilk, shiniBread))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceNumber").value("INV-000001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.subtotal").value(16.0))
                .andExpect(jsonPath("$.discount").value(1.0))
                .andExpect(jsonPath("$.tax").value(2.4))
                .andExpect(jsonPath("$.total").value(17.4))
                .andExpect(jsonPath("$.paymentMethod").value("CARD"))
                .andExpect(jsonPath("$.receiptCode").isString())
                .andExpect(jsonPath("$.cashierName").value("Test User"));

        Map<String, Object> sale = db.queryForMap("SELECT store_id, status, total, cashier_id FROM sales");
        assertThat(((Number) sale.get("store_id")).longValue()).isEqualTo(shini.storeId());
        assertThat(sale).containsEntry("status", "COMPLETED").containsEntry("total", 17.4);
        assertThat(db.queryForObject("SELECT username FROM users WHERE user_id = ?", String.class,
                sale.get("cashier_id"))).isEqualTo("shini.cashier");
        assertThat(count("sale_items")).isEqualTo(2);
        assertThat(db.queryForMap("SELECT payment_method, amount, payment_status FROM payments"))
                .containsEntry("payment_method", "CARD").containsEntry("amount", 17.4)
                .containsEntry("payment_status", "PAID");
        assertThat(count("receipts")).isEqualTo(1);
        assertThat(stock(shiniMilk)).isEqualTo(8);
        assertThat(stock(shiniBread)).isZero();
    }

    @Test
    void pricesAlwaysComeFromTheDatabase() throws Exception {
        sell(shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":1,"unitPrice":0.01}],"paymentMethod":"CASH"}"""
                .formatted(shiniMilk))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].unitPrice").value(5.0));
    }

    @Test
    void notEnoughStockSavesNothing() throws Exception {
        sell(shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":1},{"productId":%d,"quantity":4}],"paymentMethod":"CASH"}"""
                .formatted(shiniMilk, shiniBread))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.fieldErrors.Bread").value("Only 3 left in stock"));
        assertThat(count("sales")).isZero();
        assertThat(stock(shiniMilk)).isEqualTo(10);
        assertThat(db.queryForObject("SELECT next_invoice_number FROM stores WHERE store_id = ?",
                Integer.class, shini.storeId())).isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRefused() throws Exception {
        sell(shini.cashierToken(), "{\"items\":[],\"paymentMethod\":\"CASH\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.items").exists());
        sell(shini.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(shiniMilk))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.paymentMethod").exists());
        sell(shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":2}],"discountType":"PERCENT","discountValue":50,
                 "paymentMethod":"CASH"}""".formatted(shiniMilk))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.discountValue").exists());
        assertThat(count("sales")).isZero();
    }

    // ── Store isolation ───────────────────────────────────────────────────

    @Test
    void eachStoreHasItsOwnInvoiceNumbersAndProducts() throws Exception {
        sell(shini.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(shiniMilk)).andExpect(jsonPath("$.invoiceNumber").value("INV-000001"));
        sell(neshan.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(neshanJuice)).andExpect(jsonPath("$.invoiceNumber").value("INV-000001"));
        sell(shini.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(shiniMilk)).andExpect(jsonPath("$.invoiceNumber").value("INV-000002"));

        // Shini's cashier cannot sell Neshan's product.
        sell(shini.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(neshanJuice)).andExpect(status().isNotFound());
        assertThat(stock(neshanJuice)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sales WHERE store_id = ?", Integer.class,
                shini.storeId())).isEqualTo(2);
    }

    @Test
    void anotherStoreCannotSeeResumeCancelOrReturnInvoices() throws Exception {
        MvcResult r = mvc.perform(post("/api/sales/suspended").header("Authorization", bearer(neshan.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(neshanJuice)))
                .andExpect(status().isCreated()).andReturn();
        long suspendedId = jsonLong(r, "saleId");
        sell(neshan.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(neshanJuice)).andExpect(status().isCreated());

        String shiniCashier = bearer(shini.cashierToken());
        mvc.perform(get("/api/sales/suspended").header("Authorization", shiniCashier))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/sales/" + suspendedId).header("Authorization", shiniCashier))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/sales/suspended/" + suspendedId).header("Authorization", shiniCashier))
                .andExpect(status().isNotFound());
        sell(shini.cashierToken(), "{\"saleId\":%d,\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(suspendedId, shiniMilk)).andExpect(status().isNotFound());
        mvc.perform(get("/api/sales/lookup").param("code", "INV-000002").header("Authorization", shiniCashier))
                .andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT status FROM sales WHERE sale_id = ?", String.class, suspendedId))
                .isEqualTo("SUSPENDED");
    }

    // ── Suspend / resume / cancel ─────────────────────────────────────────

    @Test
    void suspendResumeAndCompleteKeepsTheInvoiceNumber() throws Exception {
        MvcResult r = mvc.perform(post("/api/sales/suspended").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":2}]}".formatted(shiniMilk)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.invoiceNumber").value("INV-000001"))
                .andReturn();
        long id = jsonLong(r, "saleId");
        assertThat(stock(shiniMilk)).isEqualTo(10); // suspending does not reserve stock

        // Another cashier of the same store sees it and changes it.
        long secondCashier = createCashier(shini.adminToken(), "shini.sara", "Sara-Pass-12");
        String sara = login("shini.sara", "Sara-Pass-12");
        mvc.perform(get("/api/sales/suspended").header("Authorization", bearer(sara)))
                .andExpect(jsonPath("$[0].invoiceNumber").value("INV-000001"));
        mvc.perform(put("/api/sales/suspended/" + id).header("Authorization", bearer(sara))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}]}"
                                .formatted(shiniMilk, shiniBread)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(2));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sale_items WHERE sale_id = ?", Integer.class, id)).isEqualTo(2);

        sell(sara, "{\"saleId\":%d,\"items\":[{\"productId\":%d,\"quantity\":1},{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"WALLET\"}"
                .formatted(id, shiniMilk, shiniBread))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceNumber").value("INV-000001"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(count("sales")).isEqualTo(1);
        assertThat(stock(shiniMilk)).isEqualTo(9);
        assertThat(secondCashier).isPositive();

        // A completed invoice cannot be resumed or canceled again.
        sell(sara, "{\"saleId\":%d,\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(id, shiniMilk)).andExpect(status().isConflict());
        mvc.perform(delete("/api/sales/suspended/" + id).header("Authorization", bearer(sara)))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelingASuspendedInvoiceKeepsItMarkedCanceled() throws Exception {
        MvcResult r = mvc.perform(post("/api/sales/suspended").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(shiniMilk)))
                .andReturn();
        long id = jsonLong(r, "saleId");
        mvc.perform(delete("/api/sales/suspended/" + id).header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        assertThat(db.queryForObject("SELECT status FROM sales WHERE sale_id = ?", String.class, id)).isEqualTo("CANCELED");
        mvc.perform(get("/api/sales/suspended").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── Returns ───────────────────────────────────────────────────────────

    @Test
    void returnByReceiptCodeRefundsAndRestoresStock() throws Exception {
        MvcResult sale = sell(shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":3}],"discountType":"PERCENT","discountValue":5,
                 "paymentMethod":"CASH"}""".formatted(shiniMilk)).andReturn();
        String receipt = jsonString(sale, "receiptCode");

        MvcResult found = mvc.perform(get("/api/sales/lookup").param("code", receipt)
                        .header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].returnableQuantity").value(3))
                .andReturn();
        long saleId = jsonLong(found, "saleId");
        long itemId = jsonLong(found, "saleItemId");

        mvc.perform(post("/api/returns").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":1,\"reason\":\"Damaged\"}"
                                .formatted(saleId, itemId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundAmount").value(5.51))
                .andExpect(jsonPath("$.refundMethod").value("CASH"))
                .andExpect(jsonPath("$.stillReturnable").value(2));

        assertThat(stock(shiniMilk)).isEqualTo(8); // 10 - 3 + 1
        assertThat(db.queryForMap("SELECT store_id, quantity, reason, refund_amount FROM returns"))
                .containsEntry("quantity", 1).containsEntry("reason", "Damaged").containsEntry("refund_amount", 5.51);
        assertThat(db.queryForObject("SELECT returned_quantity FROM sale_items WHERE sale_item_id = ?",
                Integer.class, itemId)).isEqualTo(1);

        mvc.perform(post("/api/returns").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":3,\"reason\":\"Again\"}"
                                .formatted(saleId, itemId)))
                .andExpect(status().isBadRequest());
        // The other store cannot return it.
        mvc.perform(post("/api/returns").header("Authorization", bearer(neshan.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":1,\"reason\":\"Steal\"}"
                                .formatted(saleId, itemId)))
                .andExpect(status().isNotFound());
        assertThat(count("returns")).isEqualTo(1);
    }

    @Test
    void returnsAfterTheReturnPeriodAreRefused() throws Exception {
        MvcResult sale = sell(shini.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(shiniMilk)).andReturn();
        long saleId = jsonLong(sale, "saleId");
        long itemId = jsonLong(sale, "saleItemId");
        db.update("UPDATE sales SET completed_at = '2020-01-01T10:00:00' WHERE sale_id = ?", saleId);
        mvc.perform(post("/api/returns").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":1,\"reason\":\"Late\"}"
                                .formatted(saleId, itemId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RETURN_PERIOD_OVER"));
        assertThat(stock(shiniMilk)).isEqualTo(9);
    }

    @Test
    void soldProductCanBeDeletedAndTheInvoiceKeepsItsName() throws Exception {
        MvcResult sale = sell(shini.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(shiniBread)).andReturn();
        long saleId = jsonLong(sale, "saleId");
        mvc.perform(delete("/api/products/" + shiniBread).header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/sales/" + saleId).header("Authorization", bearer(shini.cashierToken())))
                .andExpect(jsonPath("$.items[0].productName").value("Bread"))
                .andExpect(jsonPath("$.items[0].productId").isEmpty());
        assertThat(db.queryForObject("SELECT product_id FROM sale_items", Long.class)).isNull();
    }

    // ── Roles and store settings ──────────────────────────────────────────

    @Test
    void storeAdminCanSellButSystemAdminCannot() throws Exception {
        sell(shini.adminToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(shiniMilk)).andExpect(status().isCreated());
        sell(sysadminToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(shiniMilk)).andExpect(status().isForbidden());
        mvc.perform(get("/api/sales/suspended")).andExpect(status().isUnauthorized());
    }

    @Test
    void taxRateIsAPerStoreSettingOfTheSystemAdmin() throws Exception {
        mvc.perform(put("/api/admin/stores/" + shini.storeId()).header("Authorization", bearer(sysadminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shini Supermarket\",\"taxRate\":5,\"maxDiscountPercent\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxRate").value(5.0));
        mvc.perform(get("/api/store").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(jsonPath("$.taxRate").value(5.0))
                .andExpect(jsonPath("$.maxDiscountPercent").value(20.0));
        sell(shini.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":2}],\"discountType\":\"PERCENT\",\"discountValue\":15,\"paymentMethod\":\"CASH\"}"
                .formatted(shiniMilk))
                .andExpect(jsonPath("$.taxRate").value(5.0))
                .andExpect(jsonPath("$.tax").value(0.43))   // (10 - 1.5) × 5% = 0.425
                .andExpect(jsonPath("$.total").value(8.93));
        sell(neshan.cashierToken(), "{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                .formatted(neshanJuice))
                .andExpect(jsonPath("$.taxRate").value(16.0)); // other store unchanged
        mvc.perform(put("/api/admin/stores/" + shini.storeId()).header("Authorization", bearer(sysadminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Shini Supermarket\",\"taxRate\":150}"))
                .andExpect(status().isBadRequest());
    }
}
