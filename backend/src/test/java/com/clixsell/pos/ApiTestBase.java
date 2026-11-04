package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for API tests: the real application on a fresh SQLite test
 * database (target/test-clixsell.db, migrated by Flyway), plus helpers that
 * create stores, Store Admins and Cashiers through the real API and log in.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-clixsell.db?foreign_keys=on",
        "clixsell.system-admin.username=sysadmin",
        "clixsell.system-admin.password=" + ApiTestBase.SYSADMIN_PASSWORD
})
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    public static final String SYSADMIN_PASSWORD = "Test-Admin-2026";

    static {
        // Start every test run from an empty database file.
        try {
            Files.deleteIfExists(Path.of("target/test-clixsell.db"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate db;

    /** Everything except the System Admin is removed before each test. */
    @BeforeEach
    void cleanDatabase() {
        db.update("DELETE FROM user_sessions");
        db.update("DELETE FROM returns");
        db.update("DELETE FROM payments");
        db.update("DELETE FROM receipts");
        db.update("DELETE FROM sale_items");
        db.update("DELETE FROM sales");
        db.update("DELETE FROM products");
        db.update("DELETE FROM suppliers");
        db.update("DELETE FROM users WHERE role <> 'SYSTEM_ADMIN'");
        db.update("DELETE FROM stores");
    }

    /** A store with one Store Admin and one Cashier, already logged in. */
    public record StoreSetup(long storeId, String adminUsername, String adminToken,
                             String cashierUsername, String cashierToken) {
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected String login(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return jsonString(r, "token");
    }

    protected String sysadminToken() throws Exception {
        return login("sysadmin", SYSADMIN_PASSWORD);
    }

    protected long createStore(String sysToken, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/stores").header("Authorization", bearer(sysToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"address\":\"Main street\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(r, "storeId");
    }

    protected long createStoreAdmin(String sysToken, long storeId, String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/stores/" + storeId + "/admins")
                        .header("Authorization", bearer(sysToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(username, password)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(r, "userId");
    }

    protected long createCashier(String adminToken, String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/store/cashiers").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(username, password)))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(r, "userId");
    }

    /** Creates store "name" with users "&lt;prefix&gt;.admin" and "&lt;prefix&gt;.cashier", both logged in. */
    protected StoreSetup setupStore(String name, String prefix) throws Exception {
        String sys = sysadminToken();
        long storeId = createStore(sys, name);
        String admin = prefix + ".admin";
        String cashier = prefix + ".cashier";
        createStoreAdmin(sys, storeId, admin, "Admin-Pass-1");
        String adminToken = login(admin, "Admin-Pass-1");
        createCashier(adminToken, cashier, "Cashier-Pass-1");
        return new StoreSetup(storeId, admin, adminToken, cashier, login(cashier, "Cashier-Pass-1"));
    }

    /** Creates a product in the Store Admin's store and returns its id. */
    protected long createProduct(String adminToken, String name, String barcode, double price, int quantity)
            throws Exception {
        MvcResult r = mvc.perform(post("/api/products").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(name, barcode, price, quantity, 2, "Main Supplier")))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(r, "productId");
    }

    protected static String userJson(String username, String password) {
        return "{\"username\":\"%s\",\"fullName\":\"Test User\",\"password\":\"%s\"}".formatted(username, password);
    }

    protected static String productJson(String name, String barcode, double price, int qty, int min, String supplier) {
        return """
                {"name":"%s","barcode":"%s","category":"dairy","sellingPrice":%s,
                 "quantity":%d,"minStock":%d,"supplierName":"%s"}
                """.formatted(name, barcode, price, qty, min, supplier);
    }

    protected static String jsonString(MvcResult r, String field) throws Exception {
        String body = r.getResponse().getContentAsString();
        return body.replaceAll("(?s).*\"" + field + "\":\"([^\"]*)\".*", "$1");
    }

    protected static long jsonLong(MvcResult r, String field) throws Exception {
        String body = r.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll("(?s).*?\"" + field + "\":(\\d+).*", "$1"));
    }
}
