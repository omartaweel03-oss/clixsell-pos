package com.clixsell.pos.purchase;

import com.clixsell.pos.support.OwnedFiles;
import db.migration.V6__purchase_orders;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration V6 (purchase orders) on SYNTHETIC temporary databases only — never on a
 * real database. A V5 database with two stores, legacy supplier names, a deleted
 * product, Guardian Mode, activity log, sales and returns keeps every row and every
 * value; a checked backup is written first to a NEW file; a failure half-way changes
 * nothing; an existing file is never overwritten; a damaged database is not touched.
 */
public class PurchaseMigrationTest {

    private static final Path DB = Path.of("purchase-migration-test.db");
    private static final String URL = "jdbc:sqlite:" + DB + "?foreign_keys=on";
    private static final List<String> OLD_TABLES = List.of("stores", "users", "user_sessions", "suppliers", "products",
            "sales", "sale_items", "payments", "receipts", "returns", "activity_log");
    private static final List<String> TRIGGERS = List.of("trg_po_insert", "trg_po_identity", "trg_po_final",
            "trg_po_version", "trg_po_transition", "trg_po_cancel_flag", "trg_po_set_once", "trg_po_approve",
            "trg_po_message", "trg_po_no_delete", "trg_po_release", "trg_po_actor_insert", "trg_po_actor_update",
            "trg_poi_insert", "trg_poi_update", "trg_poi_delete", "trg_poe_no_update", "trg_poe_no_delete",
            "trg_poe_actor", "trg_suppliers_no_delete");
    private static final List<String> INDEXES = List.of("ux_suppliers_id_store", "ux_products_id_store",
            "ux_users_id_store", "ux_poi_open_product", "ix_po_store_status", "ix_poi_order", "ix_poi_product",
            "ix_poe_order");

    private OwnedFiles backups;

    @BeforeEach
    void watchBackups() throws Exception {
        Files.deleteIfExists(DB);
        backups = OwnedFiles.migrationBackups();
    }

    @AfterEach
    void removeFiles() throws Exception {
        Files.deleteIfExists(DB);
        backups.deleteCreated(); // only the backups THIS test wrote
    }

    private static void migrateTo(String target) {
        var config = Flyway.configure().dataSource(URL, null, null).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1");
        if (target != null) config = config.target(target);
        config.load().migrate();
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM pragma_table_info('" + table + "') ORDER BY cid")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    /** Every value (with its SQLite type) of the given columns, in rowid order. */
    private static List<String> rows(Connection c, String table, List<String> cols) throws SQLException {
        StringBuilder sel = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sel.append(" || '|' || ");
            sel.append("typeof(\"").append(cols.get(i)).append("\") || ':' || quote(\"").append(cols.get(i)).append("\")");
        }
        sel.append(" FROM ").append(table).append(" ORDER BY rowid");
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sel.toString())) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Map<String, List<String>> snapshot(String url, Map<String, List<String>> cols) throws SQLException {
        Map<String, List<String>> m = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(url)) {
            for (var e : cols.entrySet()) m.put(e.getKey(), rows(c, e.getKey(), e.getValue()));
        }
        return m;
    }

    private static Map<String, List<String>> oldColumns() throws SQLException {
        Map<String, List<String>> m = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(URL)) {
            for (String t : OLD_TABLES) m.put(t, columns(c, t));
        }
        return m;
    }

    private static String fileHash(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    /** A synthetic V5 database: two stores, legacy supplier names, deleted product, Guardian on, history. */
    static void fillV5Database(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("INSERT INTO stores (store_id, name, status, created_at, guardian_enabled, next_invoice_number) VALUES "
                    + "(1, 'Shini', 'ACTIVE', '2026-11-01T09:00:00', 1, 5), (2, 'Neshan', 'ACTIVE', '2026-11-01T09:00:00', 0, 2)");
            s.execute("INSERT INTO users (user_id, store_id, username, full_name, password_hash, role, status, created_at) VALUES "
                    + "(1, NULL, 'sysadmin', 'System', 'x', 'SYSTEM_ADMIN', 'ACTIVE', '2026-11-01T09:00:00'),"
                    + "(4, 1, 'shini.admin', 'Ahmad', 'x', 'STORE_ADMIN', 'ACTIVE', '2026-11-01T09:00:00'),"
                    + "(5, 1, 'shini.cashier', 'Lina', 'x', 'CASHIER', 'ACTIVE', '2026-11-01T09:00:00'),"
                    + "(7, 1, 'old.cashier', 'Old', 'x', 'CASHIER', 'INACTIVE', '2026-11-01T09:00:00'),"
                    + "(6, 2, 'neshan.cashier', 'Rana', 'x', 'CASHIER', 'ACTIVE', '2026-11-01T09:00:00')");
            s.execute("INSERT INTO user_sessions (user_id, token_hash, created_at, expires_at) VALUES "
                    + "(4, 'h1', '2026-11-20T09:00:00', '2026-11-30T09:00:00')");
            // legacy names that differ only by spaces (allowed by the old UNIQUE) — must stay exactly as they are
            s.execute("INSERT INTO suppliers (supplier_id, store_id, name, phone, whatsapp_number, address) VALUES "
                    + "(1, 1, 'Dairy Co', '02-1234567', NULL, 'Nablus'), (2, 1, 'Al  Quds', NULL, NULL, NULL),"
                    + "(3, 1, 'Al Quds', NULL, NULL, NULL), (4, 2, 'Juice Co', NULL, '970599000003', NULL)");
            s.execute("""
                    INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price,
                                          quantity, min_stock, created_at, updated_at, cost_price)
                    VALUES (1, 1, 1, 'Milk', '11111111', 'dairy', 5, 20, 5, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 3.2),
                           (2, 1, 2, 'Bread', '22222222', 'bakery', 2, 3, 1, '2026-11-01T09:00:00', '2026-11-01T09:00:00', NULL),
                           (4, 2, 4, 'Juice', '33333333', 'beverages', 8, 9, 2, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 5)""");
            s.execute("""
                    INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate,
                                       tax, total, created_at, updated_at, completed_at) VALUES
                      (1, 1, 5, 'INV-000001', 'COMPLETED', 12, 1, 16, 1.76, 12.76, '2026-11-02T10:00:00', '2026-11-02T10:00:00', '2026-11-02T10:00:00'),
                      (2, 1, 5, 'INV-000002', 'SUSPENDED', 5, 0, 16, 0.8, 5.8, '2026-11-03T10:00:00', '2026-11-03T10:00:00', NULL),
                      (3, 1, 7, 'INV-000003', 'CANCELED', 2, 0, 16, 0.32, 2.32, '2026-11-03T11:00:00', '2026-11-03T11:00:00', NULL),
                      (4, 2, 6, 'INV-000001', 'COMPLETED', 8, 0, 16, 1.28, 9.28, '2026-11-02T12:00:00', '2026-11-02T12:00:00', '2026-11-02T12:00:00')""");
            // sale line of a product that was deleted earlier (product_id NULL, name and barcode kept)
            s.execute("""
                    INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price,
                                            line_total, returned_quantity, unit_cost) VALUES
                      (1, 1, 1, 'Milk', '11111111', 2, 5, 10, 1, 3.2),
                      (2, 1, NULL, 'Deleted Labneh', '99999999', 1, 2, 2, 0, NULL),
                      (3, 2, 1, 'Milk', '11111111', 1, 5, 5, 0, NULL),
                      (4, 3, 2, 'Bread', '22222222', 1, 2, 2, 0, NULL),
                      (5, 4, 4, 'Juice', '33333333', 1, 8, 8, 0, 5)""");
            s.execute("INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES "
                    + "(1, 'CASH', 12.76, 'PAID', '2026-11-02T10:00:00'), (4, 'CARD', 9.28, 'PAID', '2026-11-02T12:00:00')");
            s.execute("INSERT INTO receipts (sale_id, qr_code_value, issued_at) VALUES "
                    + "(1, 'CLIXSELL-0001', '2026-11-02T10:00:00'), (4, 'CLIXSELL-0002', '2026-11-02T12:00:00')");
            s.execute("INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                    + "refund_amount, refund_method, return_date) VALUES (1, 1, 1, 1, 5, 1, 'Damaged', 5.32, 'CASH', '2026-11-04T09:00:00')");
            s.execute("INSERT INTO activity_log (store_id, user_id, event_type, entity_type, entity_id, entity_name, details, "
                    + "created_at) VALUES (1, 4, 'PRODUCT_DELETED', 'PRODUCT', 9, 'Deleted Labneh', '{\"barcode\":\"99999999\"}', "
                    + "'2026-11-05T10:00:00'), (1, 4, 'GUARDIAN_ENABLED', 'STORE', 1, 'Shini', NULL, '2026-11-05T10:01:00')");
        }
    }

    @Test
    void anEmptyDatabaseGetsTheWholeSchemaWithoutABackup() throws Exception {
        migrateTo(null);
        try (Connection c = DriverManager.getConnection(URL)) {
            for (String t : V6__purchase_orders.NEW_TABLES) {
                assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" + t + "'"))
                        .as(t).isEqualTo(1);
            }
            for (String t : TRIGGERS) {
                assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = '" + t + "'"))
                        .as(t).isEqualTo(1);
            }
            for (String i : INDEXES) {
                assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '" + i + "'"))
                        .as(i).isEqualTo(1);
            }
            assertThat(count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success = 1")).isEqualTo(1);
        }
        assertThat(backups.created("clixsell-backup-before-v6-")).as("nothing to back up").isEmpty();
    }

    @Test
    void everyExistingRowAndValueIsKeptAndACheckedBackupIsWrittenFirst() throws Exception {
        migrateTo("5");
        fillV5Database(URL);
        Map<String, List<String>> cols = oldColumns();
        Map<String, List<String>> before = snapshot(URL, cols);

        migrateTo(null); // → V6

        assertThat(snapshot(URL, cols)).as("all old columns of all old tables, value by value").isEqualTo(before);
        try (Connection c = DriverManager.getConnection(URL)) {
            assertThat(count(c, "SELECT COUNT(*) FROM suppliers WHERE active = 1")).isEqualTo(4);
            assertThat(count(c, "SELECT COUNT(*) FROM stores WHERE next_purchase_order_number = 1")).isEqualTo(2);
            assertThat(count(c, "SELECT COUNT(*) FROM suppliers WHERE name = 'Al  Quds'")).as("legacy name untouched")
                    .isEqualTo(1);
            for (String t : V6__purchase_orders.NEW_TABLES) assertThat(count(c, "SELECT COUNT(*) FROM " + t)).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("ok");
            }
            // the existing Delete Product feature still works after V6
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM products WHERE product_id = 2");
            }
        }

        List<Path> made = backups.created("clixsell-backup-before-v6-");
        assertThat(made).as("exactly one V6 backup").hasSize(1);
        String backupUrl = "jdbc:sqlite:" + made.get(0);
        assertThat(snapshot(backupUrl, cols)).as("the backup holds the V5 database exactly").isEqualTo(before);
        try (Connection b = DriverManager.getConnection(backupUrl)) {
            try (Statement s = b.createStatement(); ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("ok");
            }
            assertThat(count(b, "SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1"))
                    .as("the backup is still V5").isEqualTo(5);
            assertThat(count(b, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'purchase_orders'")).isZero();
        }
    }

    @Test
    void aFailureHalfWayChangesNothingAndARetryWritesASecondBackupWithoutTouchingTheFirst() throws Exception {
        migrateTo("5");
        fillV5Database(URL);
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE purchase_order_events (x INTEGER)"); // makes a LATE statement of V6 fail
        }
        Map<String, List<String>> cols = oldColumns();
        Map<String, List<String>> before = snapshot(URL, cols);

        assertThatThrownBy(() -> migrateTo(null)).hasMessageContaining("purchase_order_events");

        assertThat(snapshot(URL, cols)).isEqualTo(before);
        try (Connection c = DriverManager.getConnection(URL)) {
            assertThat(columns(c, "suppliers")).as("active column rolled back").doesNotContain("active");
            assertThat(columns(c, "stores")).doesNotContain("next_purchase_order_number");
            assertThat(count(c, "SELECT COUNT(*) FROM sqlite_master WHERE name IN ('purchase_orders', "
                    + "'purchase_order_items', 'ux_suppliers_id_store') OR type = 'trigger'")).isZero();
            assertThat(count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success = 1")).isZero();
        }
        List<Path> first = backups.created("clixsell-backup-before-v6-");
        assertThat(first).as("backup written before the failure").hasSize(1);
        String firstHash = fileHash(first.get(0));

        // Remove the cause and Flyway's failed row (repair), then upgrade again.
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DROP TABLE purchase_order_events");
            s.execute("DELETE FROM flyway_schema_history WHERE version = '6' AND success = 0");
        }
        Thread.sleep(5); // a different millisecond → a different backup name
        migrateTo(null);

        List<Path> all = backups.created("clixsell-backup-before-v6-");
        assertThat(all).as("a second, separate backup").hasSize(2);
        assertThat(fileHash(first.get(0))).as("the first backup was not touched").isEqualTo(firstHash);
        assertThat(snapshot(URL, cols)).isEqualTo(before);
        try (Connection c = DriverManager.getConnection(URL)) {
            assertThat(count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success = 1")).isEqualTo(1);
        }
    }

    @Test
    void anExistingFileIsNeverOverwrittenByTheBackup() throws Exception {
        migrateTo("5");
        fillV5Database(URL);
        Path existing = Path.of("clixsell-backup-before-v6-existing-test.db");
        Files.writeString(existing, "an older backup that must not change");
        try {
            String hash = fileHash(existing);
            Method write = V6__purchase_orders.class.getDeclaredMethod("writeBackup", Connection.class, String.class,
                    Map.class, Map.class, Map.class);
            write.setAccessible(true);
            try (Connection c = DriverManager.getConnection(URL)) {
                assertThatThrownBy(() -> write.invoke(null, c, existing.toString(), Map.of(), Map.of(), Map.of()))
                        .isInstanceOf(InvocationTargetException.class)
                        .hasRootCauseMessage("Backup file " + existing + " already exists; it was not touched and "
                                + "the database was not changed");
            }
            assertThat(fileHash(existing)).isEqualTo(hash);
        } finally {
            Files.deleteIfExists(existing);
        }
    }

    @Test
    void aBackupWhoseContentDiffersStopsBeforeAnyChange() throws Exception {
        migrateTo("5");
        fillV5Database(URL);
        Method write = V6__purchase_orders.class.getDeclaredMethod("writeBackup", Connection.class, String.class,
                Map.class, Map.class, Map.class);
        write.setAccessible(true);
        Path copy = Path.of("clixsell-backup-before-v6-content-test.db");
        try (Connection c = DriverManager.getConnection(URL)) {
            Map<String, List<String>> cols = Map.of("suppliers", List.of("supplier_id", "name"));
            Map<String, Long> rows = Map.of("suppliers", 4L);
            Map<String, String> wrongHash = Map.of("suppliers", "0".repeat(64));
            assertThatThrownBy(() -> write.invoke(null, c, copy.toString(), rows, wrongHash, cols))
                    .hasRootCauseMessage("Backup " + copy + " content differs from the database; "
                            + "the database was not changed");
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    @Test
    void aDatabaseWithBrokenReferencesIsNotTouchedAtAll() throws Exception {
        migrateTo("5");
        fillV5Database(URL);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB); Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = OFF");
            s.execute("INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price, "
                    + "quantity, min_stock, created_at, updated_at) VALUES (8, 1, 999, 'Orphan', '88888888', 'x', 1, 1, 1, "
                    + "'2026-11-01T09:00:00', '2026-11-01T09:00:00')");
        }
        Map<String, List<String>> cols = oldColumns();
        Map<String, List<String>> before = snapshot(URL, cols);
        assertThatThrownBy(() -> migrateTo(null)).rootCause().hasMessageContaining("broken references");
        assertThat(snapshot(URL, cols)).isEqualTo(before);
        assertThat(backups.created("clixsell-backup-before-v6-")).as("stopped before writing a backup").isEmpty();
        try (Connection c = DriverManager.getConnection(URL)) {
            assertThat(columns(c, "suppliers")).doesNotContain("active");
        }
    }

    @Test
    void aProductUsingASupplierOfAnotherStoreIsOnlyReportedNotChanged() throws Exception {
        migrateTo("5");
        fillV5Database(URL);
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("UPDATE products SET supplier_id = 4 WHERE product_id = 1"); // supplier of store 2
        }
        Map<String, List<String>> cols = oldColumns();
        Map<String, List<String>> before = snapshot(URL, cols);
        migrateTo(null);
        assertThat(snapshot(URL, cols)).isEqualTo(before);
    }
}
