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
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for API tests: the real application on a fresh SQLite test
 * database (test-clixsell.db in the isolated test folder backend/target/test-work, migrated by
 * Flyway), plus helpers that
 * create stores, Store Admins and Cashiers through the real API and log in.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:test-clixsell.db?foreign_keys=on",
        "clixsell.db.create-if-missing=true", // the test database is created on purpose
        "clixsell.system-admin.username=sysadmin",
        "clixsell.demo-data=false",
        "clixsell.backup.dir=test-backups",
        "clixsell.system-admin.password=" + ApiTestBase.SYSADMIN_PASSWORD
})
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    public static final String SYSADMIN_PASSWORD = "Test-Admin-2026";

    /**
     * Store Admins of the test stores. Not a published demo password: outside the demo profile
     * those are refused for Store Admins (see AdminPasswordSecurityApiTest).
     */
    public static final String STORE_ADMIN_PASSWORD = "Shop-Owner-Pass-1";

    /** Cashiers of the test stores. Not a published demo password (refused outside the demo profile). */
    public static final String CASHIER_PASSWORD = "Shop-Cashier-Pass-1";

    static {
        // Start every test run from an empty database file (the tests' own file, never clixsell.db).
        try {
            Files.deleteIfExists(Path.of("test-clixsell.db"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate db;

    /**
     * Everything except the System Admin is removed before each test.
     *
     * <p>Since V6 the database refuses to delete suppliers and purchase orders (triggers).
     * Only this TEST database is emptied with those triggers dropped; they are re-created
     * right after from their own saved definitions (sqlite_master), so every test runs
     * with exactly the production triggers.
     */
    @BeforeEach
    void cleanDatabase() {
        List<Map<String, Object>> triggers = db.queryForList("SELECT name, sql FROM sqlite_master WHERE type = 'trigger'");
        for (Map<String, Object> t : triggers) db.execute("DROP TRIGGER \"" + t.get("name") + "\"");
        try {
            deleteAllRows();
        } finally {
            for (Map<String, Object> t : triggers) db.execute((String) t.get("sql"));
        }
    }

    /**
     * In ONE transaction with every foreign key deferred to COMMIT: since V7 cheque refunds and
     * their cash payouts reference each other, so neither can be deleted first one by one.
     */
    private void deleteAllRows() {
        db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("PRAGMA defer_foreign_keys = ON");
                for (String table : List.of("cash_movements", "cheque_refunds", "cheque_events", "cheques",
                        "purchase_order_events", "purchase_order_items", "purchase_orders", "activity_log",
                        "user_sessions", "returns", "payments", "receipts", "sale_items", "sales", "cash_shifts",
                        "cash_drawers", "product_favorites", "product_packages", "products", "suppliers")) {
                    s.executeUpdate("DELETE FROM " + table);
                }
                s.executeUpdate("DELETE FROM users WHERE role <> 'SYSTEM_ADMIN'");
                s.executeUpdate("DELETE FROM stores");
                c.commit();
            } catch (java.sql.SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
            return null;
        });
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
        createStoreAdmin(sys, storeId, admin, STORE_ADMIN_PASSWORD);
        String adminToken = login(admin, STORE_ADMIN_PASSWORD);
        createCashier(adminToken, cashier, CASHIER_PASSWORD);
        return new StoreSetup(storeId, admin, adminToken, cashier, login(cashier, CASHIER_PASSWORD));
    }

    /**
     * Approved cash refund policy: a cash refund is paid from an open drawer that holds the cash. Tests
     * that refund cash first open a shift — as a real store must — for the cashier and the Store Admin,
     * each on its own drawer with a counted opening cash of 1,000.00.
     */
    protected void openCashShifts(StoreSetup s) throws Exception {
        String[][] users = {{s.cashierToken(), "Till C"}, {s.adminToken(), "Till A"}};
        for (String[] u : users) {
            MvcResult d = mvc.perform(post("/api/drawers").header("Authorization", bearer(s.adminToken()))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + u[1] + "\"}"))
                    .andExpect(status().isCreated()).andReturn();
            mvc.perform(post("/api/shifts").header("Authorization", bearer(u[0]))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"drawerId\":" + jsonLong(d, "drawerId") + ",\"openingCash\":1000}"))
                    .andExpect(status().isCreated());
        }
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
