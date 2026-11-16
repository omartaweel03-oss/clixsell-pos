package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Settings → Taxes and General (report Figure 3.9, UR-M12 / SR25):
 * PUT /api/store/settings – the Store Admin changes their OWN store's tax rate,
 * maximum discount, address and phone. Store from the session only.
 */
class StoreSettingsApiTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;
    private long milk; // 10.00, stock 50

    @BeforeEach
    void stores() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
        milk = createProduct(shini.adminToken(), "Milk", "11111111", 10.00, 50);
    }

    private static String settings(Object address, Object phone, Object tax, Object maxDiscount) {
        return "{\"address\":%s,\"phone\":%s,\"taxRate\":%s,\"maxDiscountPercent\":%s}"
                .formatted(q(address), q(phone), tax, maxDiscount);
    }

    private static String q(Object v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    private org.springframework.test.web.servlet.ResultActions putSettings(String token, String body)
            throws Exception {
        return mvc.perform(put("/api/store/settings").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MvcResult sell(String token, String extra) throws Exception {
        return mvc.perform(post("/api/sales").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"%s}"
                                .formatted(milk, extra)))
                .andReturn();
    }

    private Map<String, Object> store(long id) {
        return db.queryForMap("SELECT name, address, phone, tax_rate, max_discount_percent FROM stores WHERE store_id = ?", id);
    }

    @Test
    void storeAdminChangesOnlyOwnStoreAndNameStays() throws Exception {
        Map<String, Object> neshanBefore = store(neshan.storeId());
        putSettings(shini.adminToken(), settings("Al-Irsal St, Ramallah", "+970 2 295 1234", 10, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(shini.storeId()))
                .andExpect(jsonPath("$.name").value("Shini Supermarket"))
                .andExpect(jsonPath("$.address").value("Al-Irsal St, Ramallah"))
                .andExpect(jsonPath("$.phone").value("+970 2 295 1234"))
                .andExpect(jsonPath("$.taxRate").value(10.0))
                .andExpect(jsonPath("$.maxDiscountPercent").value(20.0));

        // The cashier's POS reads the new values.
        mvc.perform(get("/api/store").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(jsonPath("$.taxRate").value(10.0))
                .andExpect(jsonPath("$.maxDiscountPercent").value(20.0));
        // The other store did not change.
        assertThat(store(neshan.storeId())).isEqualTo(neshanBefore);
        assertThat(store(shini.storeId()).get("name")).isEqualTo("Shini Supermarket");

        // A blank address / phone clears it.
        putSettings(shini.adminToken(), settings("", " ", 10, 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").doesNotExist());
        assertThat(store(shini.storeId()).get("phone")).isNull();
    }

    @Test
    void aStoreIdOrNameInTheRequestIsIgnored() throws Exception {
        putSettings(shini.adminToken(),
                "{\"storeId\":%d,\"name\":\"Taken Over\",\"address\":\"X\",\"phone\":null,\"taxRate\":5,\"maxDiscountPercent\":5}"
                        .formatted(neshan.storeId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(shini.storeId()))
                .andExpect(jsonPath("$.name").value("Shini Supermarket"));
        assertThat(store(neshan.storeId()).get("tax_rate")).isEqualTo(16.0);
        assertThat(store(neshan.storeId()).get("name")).isEqualTo("Neshan Market");
    }

    @Test
    void invalidValuesAreRejectedAndNothingChanges() throws Exception {
        Map<String, Object> before = store(shini.storeId());
        putSettings(shini.adminToken(), settings(null, null, -1, 101))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.taxRate").value("Tax rate must be between 0 and 100"))
                .andExpect(jsonPath("$.fieldErrors.maxDiscountPercent").value("Maximum discount must be between 0 and 100"));
        putSettings(shini.adminToken(), settings(null, null, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.taxRate").value("Tax rate is required"))
                .andExpect(jsonPath("$.fieldErrors.maxDiscountPercent").value("Maximum discount is required"));
        putSettings(shini.adminToken(), settings("A".repeat(121), "call me", 16, 10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.address").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
        putSettings(shini.adminToken(), "{\"taxRate\":\"lots\"}").andExpect(status().isBadRequest());
        assertThat(store(shini.storeId())).isEqualTo(before);
    }

    @Test
    void onlyTheStoreAdminMayChangeSettings() throws Exception {
        Map<String, Object> before = store(shini.storeId());
        String body = settings("X", null, 0, 0);
        putSettings(shini.cashierToken(), body).andExpect(status().isForbidden());
        putSettings(sysadminToken(), body).andExpect(status().isForbidden());
        mvc.perform(put("/api/store/settings").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(store(shini.storeId())).isEqualTo(before);
    }

    @Test
    void newSalesUseTheNewTaxAndCompletedSalesKeepTheirValues() throws Exception {
        // Sold at 16 %: 10.00 + 1.60 = 11.60
        long oldSale = jsonLong(sell(shini.cashierToken(), ""), "saleId");
        // Suspended at 16 % (will be completed after the change)
        long suspended = jsonLong(mvc.perform(post("/api/sales/suspended")
                        .header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":1}]}".formatted(milk)))
                .andExpect(status().isCreated()).andReturn(), "saleId");

        putSettings(shini.adminToken(), settings(null, null, 10, 10)).andExpect(status().isOk());

        // The completed sale keeps its saved tax rate, tax and total.
        assertThat(db.queryForMap("SELECT tax_rate, tax, total FROM sales WHERE sale_id = ?", oldSale))
                .isEqualTo(Map.of("tax_rate", 16.0, "tax", 1.6, "total", 11.6));
        // A new sale uses 10 %: 10.00 + 1.00 = 11.00
        MvcResult fresh = sell(shini.cashierToken(), "");
        assertThat(fresh.getResponse().getStatus()).isEqualTo(201);
        long newSale = jsonLong(fresh, "saleId");
        assertThat(db.queryForMap("SELECT tax_rate, total FROM sales WHERE sale_id = ?", newSale))
                .isEqualTo(Map.of("tax_rate", 10.0, "total", 11.0));
        // The suspended invoice, completed now, follows the existing rule: today's store rate.
        mvc.perform(post("/api/sales").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}"
                                .formatted(suspended, milk)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taxRate").value(10.0))
                .andExpect(jsonPath("$.total").value(11.0));
        // The other store still sells at 16 %.
        assertThat(store(neshan.storeId()).get("tax_rate")).isEqualTo(16.0);
    }

    @Test
    void theNewMaximumDiscountIsEnforcedOnNewSalesOnly() throws Exception {
        // Sold with 10 % off (the default maximum): 10.00 - 1.00 = 9.00 (+16 % = 10.44)
        long earlier = jsonLong(sell(shini.cashierToken(), ",\"discountType\":\"PERCENT\",\"discountValue\":10"), "saleId");

        putSettings(shini.adminToken(), settings(null, null, 16, 5)).andExpect(status().isOk());

        MvcResult tooMuch = sell(shini.cashierToken(), ",\"discountType\":\"PERCENT\",\"discountValue\":10");
        assertThat(tooMuch.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooMuch.getResponse().getContentAsString()).contains("Discount cannot be more than 5% of the subtotal");
        MvcResult allowed = sell(shini.cashierToken(), ",\"discountType\":\"PERCENT\",\"discountValue\":5");
        assertThat(allowed.getResponse().getStatus()).isEqualTo(201);
        // The earlier sale keeps its discount and total.
        assertThat(db.queryForMap("SELECT discount, total FROM sales WHERE sale_id = ?", earlier))
                .isEqualTo(Map.of("discount", 1.0, "total", 10.44));
    }
}
