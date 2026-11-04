package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two independent supermarkets, Shini and Neshan, each with its own Store
 * Admin, Cashier, products and suppliers. Proves that one store can never
 * read or change the other store's data, and checks the database after
 * every attempt.
 */
class MultiStoreIsolationTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;

    @BeforeEach
    void twoStores() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Supermarket", "neshan");
    }

    private long addProduct(StoreSetup store, String name, String barcode, double price) throws Exception {
        MvcResult r = mvc.perform(post("/api/products").header("Authorization", bearer(store.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(name, barcode, price, 10, 2, "Dairy Co")))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(r, "productId");
    }

    @Test
    void bothStoresCanUseTheSameBarcodeAndSupplierName() throws Exception {
        long shiniMilk = addProduct(shini, "Milk 1L", "7290000000001", 5);
        long neshanMilk = addProduct(neshan, "Milk 1L", "7290000000001", 6);

        assertThat(db.queryForObject("SELECT store_id FROM products WHERE product_id = ?", Long.class, shiniMilk))
                .isEqualTo(shini.storeId());
        assertThat(db.queryForObject("SELECT store_id FROM products WHERE product_id = ?", Long.class, neshanMilk))
                .isEqualTo(neshan.storeId());
        // "Dairy Co" exists once per store
        assertThat(db.queryForList("SELECT store_id FROM suppliers ORDER BY store_id", Long.class))
                .containsExactly(shini.storeId(), neshan.storeId());
    }

    @Test
    void adminsAndCashiersSeeOnlyTheirOwnStoresProducts() throws Exception {
        addProduct(shini, "Shini Bread", "11111111", 2);
        addProduct(neshan, "Neshan Juice", "22222222", 8);

        for (String token : List.of(shini.adminToken(), shini.cashierToken())) {
            mvc.perform(get("/api/products").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Shini Bread"));
        }
        for (String token : List.of(neshan.adminToken(), neshan.cashierToken())) {
            mvc.perform(get("/api/products").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Neshan Juice"));
        }
    }

    @Test
    void storeAdminCannotReadUpdateOrDeleteAnotherStoresProduct() throws Exception {
        long neshanJuice = addProduct(neshan, "Neshan Juice", "22222222", 8);
        String attacker = bearer(shini.adminToken());

        mvc.perform(get("/api/products/" + neshanJuice).header("Authorization", attacker))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/products/" + neshanJuice).header("Authorization", attacker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Hacked", "22222222", 0.5, 0, 0, "X Co")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/products/" + neshanJuice).header("Authorization", attacker))
                .andExpect(status().isNotFound());

        Map<String, Object> row = db.queryForMap(
                "SELECT name, selling_price, store_id FROM products WHERE product_id = ?", neshanJuice);
        assertThat(row).containsEntry("name", "Neshan Juice").containsEntry("selling_price", 8.0);
        assertThat(((Number) row.get("store_id")).longValue()).isEqualTo(neshan.storeId());
    }

    @Test
    void storeIdInTheRequestBodyIsIgnored() throws Exception {
        // Even if a request claims another store, the product goes to the logged-in admin's store.
        mvc.perform(post("/api/products").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId":%d,"name":"Sneaky","barcode":"33333333","category":"dairy",
                                 "sellingPrice":1,"quantity":1,"minStock":0,"supplierName":"Co"}"""
                                .formatted(neshan.storeId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(shini.storeId()));
        assertThat(db.queryForObject("SELECT store_id FROM products WHERE barcode = '33333333'", Long.class))
                .isEqualTo(shini.storeId());
    }

    @Test
    void storeAdminSeesAndManagesOnlyTheirOwnCashiers() throws Exception {
        long neshanCashierId = db.queryForObject(
                "SELECT user_id FROM users WHERE username = 'neshan.cashier'", Long.class);

        mvc.perform(get("/api/store/cashiers").header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("shini.cashier"));

        mvc.perform(put("/api/store/cashiers/" + neshanCashierId + "/status")
                        .header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/store/cashiers/" + neshanCashierId + "/password")
                        .header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Taken-Over-1\"}"))
                .andExpect(status().isNotFound());

        assertThat(db.queryForObject("SELECT status FROM users WHERE user_id = ?", String.class, neshanCashierId))
                .isEqualTo("ACTIVE");
        login("neshan.cashier", "Cashier-Pass-1"); // old password still works
    }

    @Test
    void newCashierAlwaysJoinsTheAdminsStore() throws Exception {
        createCashier(shini.adminToken(), "shini.sara", "Sara-Pass-12");
        assertThat(db.queryForObject("SELECT store_id FROM users WHERE username = 'shini.sara'", Long.class))
                .isEqualTo(shini.storeId());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(login("shini.sara", "Sara-Pass-12"))))
                .andExpect(jsonPath("$.role").value("CASHIER"))
                .andExpect(jsonPath("$.storeName").value("Shini Supermarket"));
    }

    @Test
    void ownStoreInfoIsVisibleToItsUsersOnly() throws Exception {
        mvc.perform(get("/api/store").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Shini Supermarket"));
        mvc.perform(get("/api/store").header("Authorization", bearer(neshan.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Neshan Supermarket"));
    }
}
