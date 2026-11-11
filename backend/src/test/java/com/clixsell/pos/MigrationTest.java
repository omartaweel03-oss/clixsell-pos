package com.clixsell.pos;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upgrading a database created by the previous (single-store) version:
 * nothing may be lost, and everything must end up in "Default Store".
 */
class MigrationTest {

    private static final Path DB = Path.of("target/migration-test.db");
    private static final String URL = "jdbc:sqlite:" + DB + "?foreign_keys=on";

    @AfterEach
    void removeFiles() throws IOException {
        Files.deleteIfExists(DB);
        try (DirectoryStream<Path> backups = Files.newDirectoryStream(Path.of("."), "clixsell-backup-before-v*.db")) {
            for (Path p : backups) Files.delete(p);
        }
    }

    private static void migrate() {
        migrateTo(null);
    }

    private static void migrateTo(String target) {
        var config = Flyway.configure().dataSource(URL, null, null)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
        if (target != null) config = config.target(target);
        config.load().migrate();
    }

    /** All rows of a table as text, old columns only (to compare before / after). */
    private static java.util.List<String> rows(Connection c, String table, String columns) throws SQLException {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + columns + " FROM " + table + " ORDER BY 1")) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= n; i++) b.append(rs.getString(i)).append('|');
                out.add(b.toString());
            }
        }
        return out;
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    @Test
    void oldSingleStoreDatabaseIsMovedToDefaultStoreWithoutLosingData() throws Exception {
        Files.deleteIfExists(DB);
        // A database exactly as the previous version created it.
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            for (String sql : Files.readString(Path.of("src/main/resources/db/migration/V1__initial_schema.sql"))
                    .replaceAll("(?m)^--.*$", "").split(";")) {
                if (!sql.isBlank()) s.execute(sql);
            }
            s.execute("INSERT INTO suppliers (supplier_id, name) VALUES (1, 'Dairy Co'), (2, 'Bakery Ltd')");
            s.execute("""
                    INSERT INTO products (product_id, supplier_id, name, barcode, category, selling_price,
                                          quantity, min_stock, image, image_name, created_at, updated_at)
                    VALUES (1, 1, 'Milk', '11111111', 'dairy', 5, 24, 10, X'89504E47', 'milk.png',
                            '2026-10-01T10:00:00', '2026-10-01T10:00:00'),
                           (2, 2, 'Bread', '22222222', 'bakery', 2, 3, 10, NULL, NULL,
                            '2026-10-01T10:00:00', '2026-10-01T10:00:00')""");
        }

        migrate();

        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            assertThat(count(c, "SELECT COUNT(*) FROM stores WHERE store_id = 1 AND name = 'Default Store'")).isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM products WHERE store_id = 1")).isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM suppliers WHERE store_id = 1")).isEqualTo(2);
            // ids, links and the image are kept
            assertThat(count(c, "SELECT supplier_id FROM products WHERE product_id = 2")).isEqualTo(2);
            assertThat(count(c, "SELECT length(image) FROM products WHERE product_id = 1")).isEqualTo(4);
            assertThat(count(c, "SELECT COUNT(*) FROM users")).isZero();

            // Barcodes are now unique per store, not globally.
            s.execute("INSERT INTO stores (store_id, name, status, created_at) VALUES (2, 'Neshan', 'ACTIVE', '2026-11-01T00:00:00')");
            s.execute("INSERT INTO suppliers (supplier_id, store_id, name) VALUES (3, 2, 'Dairy Co')");
            s.execute("INSERT INTO products (store_id, supplier_id, name, barcode, category, selling_price, quantity, "
                    + "min_stock, created_at, updated_at) VALUES (2, 3, 'Milk', '11111111', 'dairy', 6, 1, 0, 'x', 'x')");
            assertThatThrownBy(() -> s.execute("INSERT INTO products (store_id, supplier_id, name, barcode, category, "
                    + "selling_price, quantity, min_stock, created_at, updated_at) "
                    + "VALUES (1, 1, 'Copy', '11111111', 'dairy', 1, 1, 0, 'x', 'x')"))
                    .hasMessageContaining("UNIQUE");
            // Foreign keys are enforced.
            assertThatThrownBy(() -> s.execute("INSERT INTO products (store_id, supplier_id, name, barcode, category, "
                    + "selling_price, quantity, min_stock, created_at, updated_at) "
                    + "VALUES (99, 1, 'Ghost', '99999999', 'dairy', 1, 1, 0, 'x', 'x')"))
                    .hasMessageContaining("FOREIGN KEY");
        }

        try (DirectoryStream<Path> backups = Files.newDirectoryStream(Path.of("."), "clixsell-backup-before-v2-*.db")) {
            assertThat(backups.iterator().hasNext()).as("backup file written before migrating").isTrue();
        }
    }

    @Test
    void newEmptyDatabaseGetsAllTablesAndNoDefaultStore() throws Exception {
        Files.deleteIfExists(DB);
        migrate();
        try (Connection c = DriverManager.getConnection(URL)) {
            assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' "
                    + "AND name IN ('stores', 'users', 'user_sessions', 'products', 'suppliers')")).isEqualTo(5);
            assertThat(count(c, "SELECT COUNT(*) FROM stores")).isZero();
        }
    }

    @Test
    void costPriceMigrationKeepsEverySaleProductAndReturn() throws Exception {
        Files.deleteIfExists(DB);
        migrateTo("3");
        // A store with sales, a partial return, a suspended and a canceled invoice (V3 schema).
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("INSERT INTO stores (store_id, name, status, created_at) VALUES (1, 'Shini', 'ACTIVE', '2026-11-01T09:00:00')");
            s.execute("INSERT INTO users (user_id, store_id, username, full_name, password_hash, role, created_at) "
                    + "VALUES (5, 1, 'shini.cashier', 'Lina', 'x', 'CASHIER', '2026-11-01T09:00:00')");
            s.execute("INSERT INTO suppliers (supplier_id, store_id, name) VALUES (1, 1, 'Dairy Co')");
            s.execute("""
                    INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price,
                                          quantity, min_stock, created_at, updated_at)
                    VALUES (1, 1, 1, 'Milk', '11111111', 'dairy', 5, 20, 5, '2026-11-01T09:00:00', '2026-11-01T09:00:00'),
                           (2, 1, 1, 'Bread', '22222222', 'bakery', 2, 3, 1, '2026-11-01T09:00:00', '2026-11-01T09:00:00')""");
            s.execute("""
                    INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate,
                                       tax, total, created_at, updated_at, completed_at) VALUES
                      (1, 1, 5, 'INV-000001', 'COMPLETED', 12, 0, 16, 1.92, 13.92, '2026-11-02T10:00:00', '2026-11-02T10:00:00', '2026-11-02T10:00:00'),
                      (2, 1, 5, 'INV-000002', 'SUSPENDED', 5, 0, 16, 0.8, 5.8, '2026-11-03T10:00:00', '2026-11-03T10:00:00', NULL),
                      (3, 1, 5, 'INV-000003', 'CANCELED', 2, 0, 16, 0.32, 2.32, '2026-11-03T11:00:00', '2026-11-03T11:00:00', NULL)""");
            s.execute("""
                    INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price,
                                            line_total, returned_quantity) VALUES
                      (1, 1, 1, 'Milk', '11111111', 2, 5, 10, 1),
                      (2, 1, 2, 'Bread', '22222222', 1, 2, 2, 0),
                      (3, 2, 1, 'Milk', '11111111', 1, 5, 5, 0),
                      (4, 3, 2, 'Bread', '22222222', 1, 2, 2, 0)""");
            s.execute("INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) "
                    + "VALUES (1, 'CASH', 13.92, 'PAID', '2026-11-02T10:00:00')");
            s.execute("INSERT INTO receipts (sale_id, qr_code_value, issued_at) VALUES (1, 'CLIXSELL-0001', '2026-11-02T10:00:00')");
            s.execute("INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                    + "refund_amount, refund_method, return_date) VALUES (1, 1, 1, 1, 5, 1, 'Damaged', 5.80, 'CASH', '2026-11-04T09:00:00')");
        }
        String[][] tables = {
                {"stores", "*"}, {"users", "*"}, {"suppliers", "*"}, {"payments", "*"}, {"receipts", "*"}, {"returns", "*"},
                {"sales", "*"},
                {"products", "product_id, store_id, supplier_id, name, barcode, category, selling_price, quantity, "
                        + "min_stock, image, image_name, created_at, updated_at"},
                {"sale_items", "sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price, "
                        + "line_total, returned_quantity"}};
        java.util.Map<String, java.util.List<String>> before = new java.util.HashMap<>();
        try (Connection c = DriverManager.getConnection(URL)) {
            for (String[] t : tables) before.put(t[0], rows(c, t[0], t[1]));
        }

        migrate(); // → V4

        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            for (String[] t : tables) {
                assertThat(rows(c, t[0], t[1])).as(t[0] + " unchanged").isEqualTo(before.get(t[0]));
            }
            // New columns exist and are empty: unknown costs are never guessed.
            assertThat(count(c, "SELECT COUNT(*) FROM products WHERE cost_price IS NULL")).isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM sale_items WHERE unit_cost IS NULL")).isEqualTo(4);
            assertThat(count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = 1"))
                    .isEqualTo(1);
            // Negative costs are refused by the database itself.
            assertThatThrownBy(() -> s.execute("UPDATE products SET cost_price = -1 WHERE product_id = 1"))
                    .hasMessageContaining("CHECK");
            s.execute("UPDATE products SET cost_price = 3.20 WHERE product_id = 1");
            assertThat(count(c, "PRAGMA foreign_key_check")).isEqualTo(-1); // no broken links
        }
        // A backup of the database was written before the change, with all the data.
        Path backup;
        try (DirectoryStream<Path> b = Files.newDirectoryStream(Path.of("."), "clixsell-backup-before-v4-*.db")) {
            backup = b.iterator().next();
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            assertThat(count(c, "SELECT COUNT(*) FROM sales")).isEqualTo(3);
            assertThat(count(c, "SELECT COUNT(*) FROM returns")).isEqualTo(1);
        }
    }
}
