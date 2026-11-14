package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stock Alerts (Figure 3.5, UR-M9 / SR22) through the real API and database:
 * the alert list and counts follow every sale, return and restock, use the
 * existing rules (out = 0, low = quantity ≤ minimum), and stay per store.
 * Data: {@code GET /api/reports/inventory} (Store Admin only).
 */
class StockAlertsApiTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;
    private long tea;     // 3 in stock, minimum 2 → normal
    private long rice;    // 2 in stock, minimum 2 → low (at the minimum)
    private long sugar;   // 0 in stock → out
    private long water;   // 50 in stock → normal

    private long product(StoreSetup s, String name, String barcode, int qty, int min) throws Exception {
        MvcResult r = mvc.perform(post("/api/products").header("Authorization", bearer(s.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(name, barcode, 4.0, qty, min, "Local Co")))
                .andExpect(status().isCreated()).andReturn();
        return jsonLong(r, "productId");
    }

    private ResultActions alerts(String token) throws Exception {
        return mvc.perform(get("/api/reports/inventory").header("Authorization", bearer(token)));
    }

    private MvcResult sell(StoreSetup s, long productId, int qty) throws Exception {
        return mvc.perform(post("/api/sales").header("Authorization", bearer(s.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":%d}],\"paymentMethod\":\"CASH\"}"
                                .formatted(productId, qty)))
                .andExpect(status().isCreated()).andReturn();
    }

    @BeforeEach
    void stores() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
        tea = product(shini, "Tea", "11111111", 3, 2);
        rice = product(shini, "Rice", "22222222", 2, 2);
        sugar = product(shini, "Sugar", "33333333", 0, 5);
        water = product(shini, "Water", "44444444", 50, 10);
        product(neshan, "Neshan Oil", "55555555", 0, 3);
    }

    @Test
    void lowOutAndNormalProductsUseTheExistingRules() throws Exception {
        alerts(shini.adminToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStock").value(1))
                .andExpect(jsonPath("$.outOfStock").value(1))
                .andExpect(jsonPath("$.inStock").value(2))
                .andExpect(jsonPath("$.reorder", hasSize(2)))
                // out of stock first, then low stock
                .andExpect(jsonPath("$.reorder[0].productId").value(sugar))
                .andExpect(jsonPath("$.reorder[0].stockStatus").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.reorder[0].quantity").value(0))
                .andExpect(jsonPath("$.reorder[0].minStock").value(5))
                .andExpect(jsonPath("$.reorder[1].productId").value(rice))
                .andExpect(jsonPath("$.reorder[1].stockStatus").value("LOW_STOCK"))
                .andExpect(jsonPath("$.reorder[1].category").value("dairy"))
                .andExpect(jsonPath("$.reorder[1].supplierName").value("Local Co"))
                .andExpect(jsonPath("$.reorder[?(@.productId == %d)]".formatted(tea)).isEmpty())
                .andExpect(jsonPath("$.reorder[?(@.productId == %d)]".formatted(water)).isEmpty());
    }

    @Test
    void alertsFollowSalesReturnsAndRestocking() throws Exception {
        // Sell 1 tea: 3 → 2 = its minimum → becomes a low-stock alert.
        MvcResult sale = sell(shini, tea, 1);
        alerts(shini.adminToken())
                .andExpect(jsonPath("$.lowStock").value(2))
                .andExpect(jsonPath("$.reorder[?(@.productId == %d)].quantity".formatted(tea)).value(2))
                .andExpect(jsonPath("$.reorder[?(@.productId == %d)].stockStatus".formatted(tea)).value("LOW_STOCK"));

        // Return it: 2 → 3 → no longer an alert.
        long saleId = jsonLong(sale, "saleId");
        long itemId = jsonLong(sale, "saleItemId");
        mvc.perform(post("/api/returns").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":1,\"reason\":\"Wrong item\"}"
                                .formatted(saleId, itemId)))
                .andExpect(status().isCreated());
        alerts(shini.adminToken())
                .andExpect(jsonPath("$.lowStock").value(1))
                .andExpect(jsonPath("$.reorder[?(@.productId == %d)]".formatted(tea)).isEmpty());

        // A suspended invoice reserves nothing: no change.
        mvc.perform(post("/api/sales/suspended").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":3}]}".formatted(tea)))
                .andExpect(status().isCreated());
        alerts(shini.adminToken()).andExpect(jsonPath("$.reorder", hasSize(2)));

        // Sell all rice: 2 → 0 → moves from low to out of stock.
        sell(shini, rice, 2);
        alerts(shini.adminToken())
                .andExpect(jsonPath("$.outOfStock").value(2))
                .andExpect(jsonPath("$.lowStock").value(0))
                .andExpect(jsonPath("$.reorder[?(@.productId == %d)].stockStatus".formatted(rice)).value("OUT_OF_STOCK"));

        // The Store Admin restocks sugar (the "Update stock" action = product edit): alert gone.
        mvc.perform(put("/api/products/" + sugar).header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Sugar", "33333333", 4.0, 20, 5, "Local Co")))
                .andExpect(status().isOk());
        alerts(shini.adminToken())
                .andExpect(jsonPath("$.outOfStock").value(1))
                .andExpect(jsonPath("$.reorder", hasSize(1)))
                .andExpect(jsonPath("$.reorder[0].productId").value(rice));
    }

    @Test
    void eachStoreSeesOnlyItsOwnAlertsAndCashiersNone() throws Exception {
        alerts(neshan.adminToken())
                .andExpect(jsonPath("$.reorder", hasSize(1)))
                .andExpect(jsonPath("$.reorder[0].name").value("Neshan Oil"))
                .andExpect(jsonPath("$.outOfStock").value(1))
                .andExpect(jsonPath("$.lowStock").value(0));
        // A sale in Shini never changes Neshan's alerts.
        sell(shini, tea, 1);
        alerts(neshan.adminToken()).andExpect(jsonPath("$.reorder", hasSize(1)));
        // Store id in the request is ignored; cashiers and the System Admin are refused.
        mvc.perform(get("/api/reports/inventory?storeId=" + shini.storeId())
                        .header("Authorization", bearer(neshan.adminToken())))
                .andExpect(jsonPath("$.reorder[0].name").value("Neshan Oil"));
        alerts(shini.cashierToken()).andExpect(status().isForbidden());
        alerts(sysadminToken()).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/inventory")).andExpect(status().isUnauthorized());
    }
}
