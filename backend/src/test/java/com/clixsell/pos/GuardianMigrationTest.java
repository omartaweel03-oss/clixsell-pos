package com.clixsell.pos;

import db.migration.V5__guardian_mode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration V5 (Guardian Mode) on temporary databases only: an existing V4
 * database with two stores keeps every row, gets a CHECKED backup first, every
 * store starts with Guardian Mode OFF and the activity log starts empty.
 */
class GuardianMigrationTest {

    private static final Path DB = Path.of("target/guardian-migration-test.db");
    private static final String URL = "jdbc:sqlite:" + DB + "?foreign_keys=on";
    private static final String[] TABLES = {"stores", "users", "user_sessions", "suppliers", "products", "sales",
            "sale_items", "payments", "receipts", "returns"};

    @AfterEach
    void removeFiles() throws IOException {
        Files.deleteIfExists(DB);
        try (DirectoryStream<Path> backups = Files.newDirectoryStream(Path.of("."), "clixsell-backup-before-v*.db")) {
            for (Path p : backups) Files.delete(p);
        }
    }

    private static void migrateTo(String target) {
        var config = Flyway.configure().dataSource(URL, null, null)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
        if (target != null) config = config.target(target);
        config.load().migrate();
    }

    private static List<String> rows(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
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

    /** Two stores with users, products (with and without cost), sales, a return, suspended and canceled invoices. */
    private static void fillV4Database() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("INSERT INTO stores (store_id, name, status, created_at) VALUES "
                    + "(1, 'Shini', 'ACTIVE', '2026-11-01T09:00:00'), (2, 'Neshan', 'ACTIVE', '2026-11-01T09:00:00')");
            s.execute("INSERT INTO users (user_id, store_id, username, full_name, password_hash, role, created_at) VALUES "
                    + "(5, 1, 'shini.cashier', 'Lina', 'x', 'CASHIER', '2026-11-01T09:00:00'),"
                    + "(6, 2, 'neshan.cashier', 'Rana', 'x', 'CASHIER', '2026-11-01T09:00:00')");
            s.execute("INSERT INTO suppliers (supplier_id, store_id, name) VALUES (1, 1, 'Dairy Co'), (2, 2, 'Juice Co')");
            s.execute("""
                    INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price,
                                          quantity, min_stock, created_at, updated_at, cost_price)
                    VALUES (1, 1, 1, 'Milk', '11111111', 'dairy', 5, 20, 5, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 3.2),
                           (2, 1, 1, 'Bread', '22222222', 'bakery', 2, 3, 1, '2026-11-01T09:00:00', '2026-11-01T09:00:00', NULL),
                           (3, 2, 2, 'Juice', '33333333', 'beverages', 8, 9, 2, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 5)""");
            s.execute("""
                    INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate,
                                       tax, total, created_at, updated_at, completed_at) VALUES
                      (1, 1, 5, 'INV-000001', 'COMPLETED', 12, 1, 16, 1.76, 12.76, '2026-11-02T10:00:00', '2026-11-02T10:00:00', '2026-11-02T10:00:00'),
                      (2, 1, 5, 'INV-000002', 'SUSPENDED', 5, 0, 16, 0.8, 5.8, '2026-11-03T10:00:00', '2026-11-03T10:00:00', NULL),
                      (3, 1, 5, 'INV-000003', 'CANCELED', 2, 0, 16, 0.32, 2.32, '2026-11-03T11:00:00', '2026-11-03T11:00:00', NULL),
                      (4, 2, 6, 'INV-000001', 'COMPLETED', 8, 0, 16, 1.28, 9.28, '2026-11-02T12:00:00', '2026-11-02T12:00:00', '2026-11-02T12:00:00')""");
            s.execute("""
                    INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price,
                                            line_total, returned_quantity, unit_cost) VALUES
                      (1, 1, 1, 'Milk', '11111111', 2, 5, 10, 1, 3.2),
                      (2, 1, 2, 'Bread', '22222222', 1, 2, 2, 0, NULL),
                      (3, 2, 1, 'Milk', '11111111', 1, 5, 5, 0, NULL),
                      (4, 3, 2, 'Bread', '22222222', 1, 2, 2, 0, NULL),
                      (5, 4, 3, 'Juice', '33333333', 1, 8, 8, 0, 5)""");
            s.execute("INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES "
                    + "(1, 'CASH', 12.76, 'PAID', '2026-11-02T10:00:00'), (4, 'CARD', 9.28, 'PAID', '2026-11-02T12:00:00')");
            s.execute("INSERT INTO receipts (sale_id, qr_code_value, issued_at) VALUES "
                    + "(1, 'CLIXSELL-0001', '2026-11-02T10:00:00'), (4, 'CLIXSELL-0002', '2026-11-02T12:00:00')");
            s.execute("INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                    + "refund_amount, refund_method, return_date) VALUES (1, 1, 1, 1, 5, 1, 'Damaged', 5.32, 'CASH', '2026-11-04T09:00:00')");
        }
    }

    @Test
    void existingDataIsKeptBackedUpAndCheckedAndGuardianStartsOff() throws Exception {
        Files.deleteIfExists(DB);
        migrateTo("4");
        fillV4Database();
        Map<String, List<String>> before = new HashMap<>();
        try (Connection c = DriverManager.getConnection(URL)) {
            for (String t : TABLES) before.put(t, rows(c, t));
        }

        migrateTo(null); // → V5

        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            for (String t : TABLES) {
                if (t.equals("stores")) continue; // has the new column; checked below
                assertThat(rows(c, t)).as(t + " unchanged").isEqualTo(before.get(t));
            }
            // stores: the old columns are unchanged and the new column is 0 (OFF) for every store.
            List<String> storesNow = new ArrayList<>();
            for (String r : rows(c, "stores")) storesNow.add(r.substring(0, r.lastIndexOf('|', r.length() - 2) + 1));
            assertThat(storesNow).isEqualTo(before.get("stores"));
            assertThat(count(c, "SELECT COUNT(*) FROM stores WHERE guardian_enabled = 0")).isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM activity_log")).isZero(); // nothing invented
            assertThat(count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success = 1"))
                    .isEqualTo(1);
            assertThat(count(c, "PRAGMA foreign_key_check")).isEqualTo(-1);
            // The database itself refuses wrong values.
            assertThatThrownBy(() -> s.execute("UPDATE stores SET guardian_enabled = 2 WHERE store_id = 1"))
                    .hasMessageContaining("CHECK");
            assertThatThrownBy(() -> s.execute("INSERT INTO activity_log (store_id, user_id, event_type, created_at) "
                    + "VALUES (1, 5, 'SOMETHING_ELSE', '2026-11-05T10:00:00')")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> s.execute("INSERT INTO activity_log (store_id, user_id, event_type, created_at) "
                    + "VALUES (99, 5, 'PRODUCT_DELETED', '2026-11-05T10:00:00')")).hasMessageContaining("FOREIGN KEY");
            s.execute("INSERT INTO activity_log (store_id, user_id, event_type, entity_type, entity_id, entity_name, "
                    + "created_at) VALUES (1, 5, 'INVOICE_CANCELED', 'SALE', 3, 'INV-000003', '2026-11-05T10:00:00')");
            assertThat(count(c, "SELECT COUNT(*) FROM activity_log")).isEqualTo(1);
        }

        // The backup was written BEFORE the change and holds every row of every table.
        Path backup;
        try (DirectoryStream<Path> b = Files.newDirectoryStream(Path.of("."), "clixsell-backup-before-v5-*.db")) {
            backup = b.iterator().next();
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("ok");
            }
            for (String t : TABLES) assertThat(rows(c, t)).as("backup of " + t).isEqualTo(before.get(t));
            // The backup is the V4 database: no Guardian column, no activity log.
            assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'activity_log'")).isZero();
        }
    }

    @Test
    void aBackupThatDoesNotHoldEveryRowStopsTheMigrationBeforeAnyChange() throws Exception {
        Files.deleteIfExists(DB);
        migrateTo("4");
        fillV4Database();
        Path copy = Path.of("target/guardian-backup-check.db");
        Files.deleteIfExists(copy);
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("VACUUM INTO '" + copy + "'");
        }
        Method verify = V5__guardian_mode.class.getDeclaredMethod("verifyBackup", String.class, Map.class);
        verify.setAccessible(true);
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("sales", 4L);
        expected.put("returns", 1L);
        verify.invoke(null, copy.toString(), expected); // a correct copy passes
        expected.put("sales", 5L);
        assertThatThrownBy(() -> verify.invoke(null, copy.toString(), expected))
                .hasRootCauseMessage("Backup " + copy + " does not hold every row {sales=5, returns=1} → "
                        + "{sales=4, returns=1}; the database was not changed");
        Files.deleteIfExists(copy);
    }

    @Test
    void aNewEmptyDatabaseGetsTheGuardianTablesWithoutABackup() throws Exception {
        Files.deleteIfExists(DB);
        migrateTo(null);
        try (Connection c = DriverManager.getConnection(URL)) {
            assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'activity_log'"))
                    .isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM pragma_table_info('stores') WHERE name = 'guardian_enabled'"))
                    .isEqualTo(1);
            assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' "
                    + "AND name IN ('idx_activity_store_time', 'idx_activity_store_type')")).isEqualTo(2);
        }
        try (DirectoryStream<Path> b = Files.newDirectoryStream(Path.of("."), "clixsell-backup-before-v5-*.db")) {
            assertThat(b.iterator().hasNext()).as("nothing to back up in an empty database").isFalse();
        }
    }
}
