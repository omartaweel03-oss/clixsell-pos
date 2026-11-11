package com.clixsell.pos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upgrading a database made by the multi-store version (V2) to the sales
 * version (V3), as will happen on an existing installation: every store,
 * user and product is kept, the new store settings get their defaults, and
 * selling works right after the upgrade.
 *
 * <p>Runs with the "demo" profile on purpose: a database that already has
 * stores must not receive demo data or have any record changed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/upgrade-v2-clixsell.db?foreign_keys=on",
        "clixsell.system-admin.password=Upgrade-Admin-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class UpgradeToSalesVersionTest {

    private static final Path DB = Path.of("target/upgrade-v2-clixsell.db");
    private static final String URL = "jdbc:sqlite:" + DB + "?foreign_keys=on";

    static {
        try {
            Files.deleteIfExists(DB);
            // 1) An empty database migrated only up to V2 (the multi-store version).
            Flyway.configure().dataSource(URL, null, null)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true).baselineVersion("1")
                    .target("2")
                    .load().migrate();
            // 2) Data exactly as the multi-store version stored it.
            String hash = new BCryptPasswordEncoder().encode("Cashier-Pass-1");
            try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
                s.execute("""
                        INSERT INTO stores (store_id, name, address, phone, status, created_at) VALUES
                          (1, 'Shini Supermarket', 'Ramallah', '0599000000', 'ACTIVE', '2026-11-01T09:00:00'),
                          (2, 'Neshan Market', 'Nablus', NULL, 'ACTIVE', '2026-11-01T09:00:00')""");
                s.execute("INSERT INTO users (user_id, store_id, username, full_name, password_hash, role, status, created_at) "
                        + "VALUES (10, 1, 'old.cashier', 'Old Cashier', '" + hash + "', 'CASHIER', 'ACTIVE', '2026-11-01T09:00:00'),"
                        + " (11, 2, 'neshan.old', 'Neshan Cashier', '" + hash + "', 'CASHIER', 'ACTIVE', '2026-11-01T09:00:00')");
                s.execute("INSERT INTO suppliers (supplier_id, store_id, name) VALUES (1, 1, 'Dairy Co'), (2, 2, 'Dairy Co')");
                s.execute("""
                        INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price,
                                              quantity, min_stock, image, image_name, created_at, updated_at) VALUES
                          (1, 1, 1, 'Milk', '11111111', 'dairy', 5, 24, 10, X'89504E47', 'milk.png',
                           '2026-11-01T09:00:00', '2026-11-01T09:00:00'),
                          (2, 1, 1, 'Labaneh', '22222222', 'dairy', 9, 3, 5, NULL, NULL,
                           '2026-11-01T09:00:00', '2026-11-01T09:00:00'),
                          (3, 2, 2, 'Milk', '11111111', 'dairy', 6, 7, 2, NULL, NULL,
                           '2026-11-01T09:00:00', '2026-11-01T09:00:00')""");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare the V2 database", e);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate db;

    private String login(String username) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"Cashier-Pass-1\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        return body.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void upgradeKeepsEverythingAndSellingWorks() throws Exception {
        // V3 was applied on top of V2.
        assertThat(db.queryForList("select version from flyway_schema_history where success = 1 order by installed_rank",
                String.class)).containsSubsequence("2", "3");

        // Nothing lost or changed, and no demo data added (the database already had stores).
        assertThat(db.queryForList("select name from stores order by store_id", String.class))
                .containsExactly("Shini Supermarket", "Neshan Market");
        assertThat(db.queryForObject("select count(*) from users where role <> 'SYSTEM_ADMIN'", Integer.class)).isEqualTo(2);
        assertThat(db.queryForList("select name || ':' || quantity from products order by product_id", String.class))
                .containsExactly("Milk:24", "Labaneh:3", "Milk:7");
        assertThat(db.queryForObject("select length(image) from products where product_id = 1", Integer.class)).isEqualTo(4);

        // New store settings get their defaults.
        assertThat(db.queryForList("select tax_rate || '/' || max_discount_percent || '/' || next_invoice_number "
                + "from stores order by store_id", String.class)).containsExactly("16.0/10.0/1", "16.0/10.0/1");

        // The old cashier logs in and sells right away.
        String token = login("old.cashier");
        mvc.perform(get("/api/store").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taxRate").value(16.0));
        mvc.perform(post("/api/sales").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":1,\"quantity\":2}],\"paymentMethod\":\"CASH\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceNumber").value("INV-000001"))
                .andExpect(jsonPath("$.total").value(11.6)); // 2 × 5.00 + 16 %

        assertThat(db.queryForObject("select quantity from products where product_id = 1", Integer.class)).isEqualTo(22);
        // The other store is untouched.
        assertThat(db.queryForObject("select quantity from products where product_id = 3", Integer.class)).isEqualTo(7);
        assertThat(db.queryForObject("select count(*) from sales where store_id = 2", Integer.class)).isZero();
    }
}
