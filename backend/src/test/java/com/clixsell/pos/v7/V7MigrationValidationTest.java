package com.clixsell.pos.v7;

import db.migration.V7__store_operations;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static com.clixsell.pos.v7.CashRoundingProposal.adjustment;
import static com.clixsell.pos.v7.CashRoundingProposal.cashRefund;
import static com.clixsell.pos.v7.CashRoundingProposal.roundToStep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation of V7 (db/migration/V7__store_operations) on disposable V6 databases with
 * representative history: the backup procedure, the payments rebuild, every new constraint and
 * trigger, failure handling, races, closed-shift immutability, and the cash rounding / refund rules.
 * No real or operational database is ever opened.
 */
public class V7MigrationValidationTest {

    @TempDir
    Path dir;
    private Path db;

    @BeforeEach
    void newV6Database() throws Exception {
        db = dir.resolve("clixsell.db");
        Flyway.configure().dataSource(url(), null, null).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").target("6").load().migrate();
    }

    private String url() {
        return "jdbc:sqlite:" + db + "?foreign_keys=on";
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url());
    }

    private static void sql(Connection c, String... statements) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (String q : statements) s.execute(q);
        }
    }

    private static long n(Connection c, String q) throws SQLException {
        return V7__store_operations.count(c, q);
    }

    private static String one(Connection c, String q) throws SQLException {
        return V7__store_operations.one(c, q);
    }

    private static String fileHash(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    /** Two stores, users of every role, suppliers, products, sales of every status and method, returns, logs, a PO. */
    private void fillV6() throws SQLException {
        try (Connection c = open()) {
            sql(c,
                "INSERT INTO stores (store_id, name, status, created_at, guardian_enabled, next_invoice_number) VALUES "
                        + "(1, 'Shini', 'ACTIVE', '2026-11-01T09:00:00', 1, 7), (2, 'Neshan', 'ACTIVE', '2026-11-01T09:00:00', 0, 2)",
                "INSERT INTO users (user_id, store_id, username, full_name, password_hash, role, status, created_at) VALUES "
                        + "(1, NULL, 'sysadmin', 'System', 'x', 'SYSTEM_ADMIN', 'ACTIVE', '2026-11-01T09:00:00'),"
                        + "(4, 1, 'shini.admin', 'Ahmad', 'x', 'STORE_ADMIN', 'ACTIVE', '2026-11-01T09:00:00'),"
                        + "(5, 1, 'shini.cashier', 'Lina', 'x', 'CASHIER', 'ACTIVE', '2026-11-01T09:00:00'),"
                        + "(7, 1, 'shini.cashier2', 'Sami', 'x', 'CASHIER', 'ACTIVE', '2026-11-01T09:00:00'),"
                        + "(8, 2, 'neshan.admin', 'Huda', 'x', 'STORE_ADMIN', 'ACTIVE', '2026-11-01T09:00:00'),"
                        + "(6, 2, 'neshan.cashier', 'Rana', 'x', 'CASHIER', 'ACTIVE', '2026-11-01T09:00:00')",
                "INSERT INTO user_sessions (user_id, token_hash, created_at, expires_at) VALUES "
                        + "(4, 'h1', '2026-11-20T09:00:00', '2026-11-30T09:00:00')",
                "INSERT INTO suppliers (supplier_id, store_id, name, phone, whatsapp_number, address) VALUES "
                        + "(1, 1, 'Dairy Co', '02-1234567', '970599000001', 'Nablus'), (2, 2, 'Juice Co', NULL, NULL, NULL)",
                """
                INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price,
                                      quantity, min_stock, created_at, updated_at, cost_price) VALUES
                  (1, 1, 1, 'Water 1.5L', '11111111', 'beverages', 2, 240, 24, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 1.1),
                  (2, 1, 1, 'Milk', '22222222', 'dairy', 5, 20, 5, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 3.2),
                  (3, 2, 2, 'Juice', '33333333', 'beverages', 8, 9, 2, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 5)""",
                """
                INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate,
                                   tax, total, created_at, updated_at, completed_at) VALUES
                  (1, 1, 5, 'INV-000001', 'COMPLETED', 12, 1, 16, 1.76, 12.76, '2026-11-02T10:00:00', '2026-11-02T10:00:00', '2026-11-02T10:00:00'),
                  (2, 1, 5, 'INV-000002', 'COMPLETED', 10, 0, 16, 1.6, 11.6, '2026-11-02T11:00:00', '2026-11-02T11:00:00', '2026-11-02T11:00:00'),
                  (3, 1, 7, 'INV-000003', 'COMPLETED', 5, 0, 16, 0.8, 5.8, '2026-11-02T12:00:00', '2026-11-02T12:00:00', '2026-11-02T12:00:00'),
                  (4, 1, 7, 'INV-000004', 'COMPLETED', 2, 0, 16, 0.32, 2.32, '2026-11-02T13:00:00', '2026-11-02T13:00:00', '2026-11-02T13:00:00'),
                  (5, 1, 5, 'INV-000005', 'SUSPENDED', 5, 0, 16, 0.8, 5.8, '2026-11-03T10:00:00', '2026-11-03T10:00:00', NULL),
                  (6, 1, 5, 'INV-000006', 'CANCELED', 2, 0, 16, 0.32, 2.32, '2026-11-03T11:00:00', '2026-11-03T11:00:00', NULL),
                  (7, 2, 6, 'INV-000001', 'COMPLETED', 8, 0, 16, 1.28, 9.28, '2026-11-02T12:00:00', '2026-11-02T12:00:00', '2026-11-02T12:00:00')""",
                """
                INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price,
                                        line_total, returned_quantity, unit_cost) VALUES
                  (1, 1, 2, 'Milk', '22222222', 2, 5, 10, 1, 3.2),
                  (2, 1, NULL, 'Deleted Labneh', '99999999', 1, 2, 2, 0, NULL),
                  (3, 2, 2, 'Milk', '22222222', 2, 5, 10, 0, 3.2),
                  (4, 3, 2, 'Milk', '22222222', 1, 5, 5, 0, 3.2),
                  (5, 4, 1, 'Water 1.5L', '11111111', 1, 2, 2, 0, 1.1),
                  (6, 5, 2, 'Milk', '22222222', 1, 5, 5, 0, NULL),
                  (7, 6, 1, 'Water 1.5L', '11111111', 1, 2, 2, 0, NULL),
                  (8, 7, 3, 'Juice', '33333333', 1, 8, 8, 0, 5)""",
                // a deleted payment row earlier: AUTOINCREMENT high-water mark (12) above the max id (11)
                "INSERT INTO payments (payment_id, sale_id, payment_method, amount, payment_status, paid_at) VALUES "
                        + "(8, 1, 'CASH', 12.76, 'PAID', '2026-11-02T10:00:00'), (9, 2, 'CARD', 11.6, 'PAID', '2026-11-02T11:00:00'),"
                        + "(10, 3, 'WALLET', 5.8, 'PAID', '2026-11-02T12:00:00'), (11, 4, 'BANK_TRANSFER', 2.32, 'PAID', '2026-11-02T13:00:00'),"
                        + "(12, 7, 'CARD', 9.28, 'PAID', '2026-11-02T12:00:00')",
                "UPDATE sqlite_sequence SET seq = 20 WHERE name = 'payments'",
                "INSERT INTO receipts (sale_id, qr_code_value, issued_at) VALUES (1, 'CLIXSELL-0001', '2026-11-02T10:00:00'),"
                        + "(2, 'CLIXSELL-0002', '2026-11-02T11:00:00'), (7, 'CLIXSELL-0003', '2026-11-02T12:00:00')",
                "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                        + "refund_amount, refund_method, return_date) VALUES (1, 1, 1, 2, 5, 1, 'Damaged', 5.32, 'CASH', '2026-11-04T09:00:00')",
                "INSERT INTO activity_log (store_id, user_id, event_type, entity_type, entity_id, entity_name, details, created_at) "
                        + "VALUES (1, 4, 'GUARDIAN_ENABLED', 'STORE', 1, 'Shini', NULL, '2026-11-05T10:01:00')",
                "INSERT INTO purchase_orders (store_id, order_number, supplier_id, supplier_name, status, source, created_by, "
                        + "created_at, client_request_id, request_hash) VALUES (1, 'PO-000001', 1, 'Dairy Co', 'PENDING_APPROVAL', "
                        + "'MANUAL', 4, '2026-11-06T09:00:00', '00000000-0000-4000-8000-000000000001', '" + "a".repeat(64) + "')"
            );
        }
    }

    private Path migrate() throws Exception {
        return V7__store_operations.migrate(db, null, null).backup();
    }

    // ── 1. A populated V6 database ──────────────────────────────────────────────────

    @Test
    void populatedV6_checkedBackupFirst_everyOldRowAndObjectKept_newTablesEmpty() throws Exception {
        fillV6();
        Map<String, List<String>> cols = new LinkedHashMap<>();
        Map<String, String> before;
        TreeSet<String> objectsBefore;
        Map<String, Long> rowsBefore;
        try (Connection c = open()) {
            for (String t : V7__store_operations.tables(c)) cols.put(t, V7__store_operations.columns(c, t));
            before = V7__store_operations.hashes(c, cols);
            rowsBefore = V7__store_operations.counts(c, cols.keySet());
            objectsBefore = V7__store_operations.objects(c);
        }
        String dbHashBefore = fileHash(db);

        Path backup = migrate();

        // backup: next to the database, safe unique name, identical to the database before V7
        assertThat(backup.getParent()).isEqualTo(db.toAbsolutePath().getParent());
        assertThat(backup.getFileName().toString()).matches("clixsell-backup-before-v7-\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}\\.db");
        try (Connection b = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            assertThat(one(b, "PRAGMA integrity_check")).isEqualTo("ok");
            assertThat(V7__store_operations.hashes(b, cols)).isEqualTo(before);
            assertThat(n(b, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'cash_shifts'")).isZero();
        }
        assertThat(fileHash(db)).isNotEqualTo(dbHashBefore); // the database itself was migrated

        try (Connection c = open()) {
            assertThat(V7__store_operations.hashes(c, cols)).as("old values").isEqualTo(before);
            assertThat(V7__store_operations.counts(c, cols.keySet())).isEqualTo(rowsBefore);
            assertThat(V7__store_operations.objects(c)).containsAll(objectsBefore);
            for (String t : V7__store_operations.NEW_TABLES) assertThat(n(c, "SELECT COUNT(*) FROM " + t)).as(t).isZero();
            assertThat(one(c, "PRAGMA integrity_check")).isEqualTo("ok");
            assertThat(n(c, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
            // historical payments: same ids, methods, amounts; AUTOINCREMENT high-water mark kept (20)
            assertThat(one(c, "SELECT group_concat(payment_id || ':' || payment_method || ':' || amount, ',') FROM "
                    + "(SELECT * FROM payments ORDER BY payment_id)"))
                    .isEqualTo("8:CASH:12.76,9:CARD:11.6,10:WALLET:5.8,11:BANK_TRANSFER:2.32,12:CARD:9.28");
            assertThat(one(c, "SELECT seq FROM sqlite_sequence WHERE name = 'payments'")).isEqualTo("20");
            // reports read exactly what they read before
            assertThat(one(c, "SELECT printf('%.2f', sum(total)) FROM sales WHERE status = 'COMPLETED' AND store_id = 1"))
                    .isEqualTo("32.48");
            assertThat(one(c, "SELECT printf('%.2f', sum(refund_amount)) FROM returns")).isEqualTo("5.32");
            // neutral defaults: nothing changes until a Store Admin enables a feature
            assertThat(n(c, "SELECT COUNT(*) FROM stores WHERE require_cash_shift = 0 AND cash_rounding_minor = 0")).isEqualTo(2);
            // a new payment continues after the high-water mark, never reusing an old id
            sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, total, "
                    + "created_at, updated_at, completed_at) VALUES (20, 1, 5, 'INV-000020', 'COMPLETED', 10, 0, 16, 1.6, 11.6, "
                    + "'2026-11-28T09:00:00', '2026-11-28T09:00:00', '2026-11-28T09:00:00')",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES "
                            + "(20, 'CHEQUE', 11.6, 'PAID', '2026-11-28T09:00:00')");
            assertThat(one(c, "SELECT payment_id FROM payments WHERE sale_id = 20")).isEqualTo("21");
        }
        // the procedure refuses to run twice (already V7 objects): nothing is changed
        String afterHash = fileHash(db);
        assertThatThrownBy(this::migrate).hasMessageContaining("already exists");
        assertThat(fileHash(db)).isEqualTo(afterHash);
    }

    @Test
    void emptyV6DatabaseNeedsNoBackup() throws Exception {
        assertThat(migrate()).isNull();
        try (Connection c = open()) {
            assertThat(n(c, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'cheques'")).isEqualTo(1);
        }
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("clixsell-backup")).count()).isZero();
        }
    }

    // ── 2. Failures: before the backup, in the backup, in the migration ────────────

    @Test
    void preflight_twoPaymentsForOneSale_refusesBeforeAnyChange() throws Exception {
        fillV6();
        try (Connection c = open()) {
            sql(c, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) "
                    + "VALUES (1, 'CASH', 1, 'PAID', '2026-11-02T10:00:00')");
        }
        String h = fileHash(db);
        assertThatThrownBy(this::migrate).hasMessageContaining("more than one payment");
        assertThat(fileHash(db)).isEqualTo(h);
        try (Stream<Path> s = Files.list(dir)) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("clixsell-backup")).count()).as("no backup").isZero();
        }
    }

    @Test
    void preflight_brokenReference_refusesBeforeAnyChange() throws Exception {
        fillV6();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db)) { // foreign keys OFF to plant it
            sql(c, "INSERT INTO receipts (sale_id, qr_code_value, issued_at) VALUES (999, 'ORPHAN', '2026-11-02')");
        }
        String h = fileHash(db);
        assertThatThrownBy(this::migrate).hasMessageContaining("Broken references");
        assertThat(fileHash(db)).isEqualTo(h);
    }

    @Test
    void backupNameTaken_backupFails_databaseUnchanged() throws Exception {
        fillV6();
        Files.writeString(dir.resolve("taken.db"), "someone's file");
        String h = fileHash(db);
        assertThatThrownBy(() -> V7__store_operations.migrate(db, null, "taken.db"))
                .isInstanceOf(V7__store_operations.BackupFailed.class)
                .hasMessageContaining("not changed");
        assertThat(fileHash(db)).isEqualTo(h);
        assertThat(Files.readString(dir.resolve("taken.db"))).isEqualTo("someone's file"); // never overwritten
    }

    @Test
    void failureAfterPaymentsWereDropped_rollsEverythingBack() throws Exception {
        fillV6();
        Map<String, List<String>> cols = new LinkedHashMap<>();
        Map<String, String> before;
        TreeSet<String> schemaBefore;
        try (Connection c = open()) {
            for (String t : V7__store_operations.tables(c)) cols.put(t, V7__store_operations.columns(c, t));
            before = V7__store_operations.hashes(c, cols);
            schemaBefore = V7__store_operations.objects(c);
        }
        int drop = V7__store_operations.SCHEMA.indexOf("DROP TABLE payments");
        assertThat(drop).isPositive();
        assertThatThrownBy(() -> V7__store_operations.migrate(db, i -> {
            if (i == drop) throw new IllegalStateException("simulated crash after DROP TABLE payments");
        }, null)).hasMessageContaining("simulated crash");
        try (Connection c = open()) {
            assertThat(V7__store_operations.objects(c)).isEqualTo(schemaBefore); // schema exactly V6
            assertThat(V7__store_operations.hashes(c, cols)).isEqualTo(before);   // every value
            assertThat(one(c, "SELECT seq FROM sqlite_sequence WHERE name = 'payments'")).isEqualTo("20");
            assertThat(one(c, "PRAGMA integrity_check")).isEqualTo("ok");
            assertThat(n(c, "SELECT COUNT(*) FROM pragma_table_info('stores') WHERE name = 'require_cash_shift'")).isZero();
        }
        try (Stream<Path> s = Files.list(dir)) { // the verified backup is kept for recovery
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("clixsell-backup-before-v7-")).count()).isEqualTo(1);
        }
        // and the procedure can then be run again successfully
        assertThat(migrate()).isNotNull();
    }

    // ── 3. Payments and cheques ─────────────────────────────────────────────────────

    private static final String SALE = "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, "
            + "discount, tax_rate, tax, total, created_at, updated_at, completed_at, shift_id, rounding_minor) VALUES ";

    @Test
    void payments_cheque_oneRowPerSale_amountRule_cashOnlyRounding() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            // 19.97 cash, rounded to 20.00 (step 10): payment 20.00, tendered 50, change 30
            sql(c, SALE + "(30, 1, 5, 'INV-000030', 'COMPLETED', 17.22, 0, 16, 2.75, 19.97, 'a', 'a', 'a', NULL, 3)",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at, tendered_minor, change_minor) "
                            + "VALUES (30, 'CASH', 20.00, 'PAID', 'a', 5000, 3000)");
            assertThatThrownBy(() -> sql(c, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) "
                    + "VALUES (30, 'CASH', 20.00, 'PAID', 'a')")).hasMessageContaining("UNIQUE"); // one per sale
            sql(c, SALE + "(31, 1, 5, 'INV-000031', 'COMPLETED', 17.22, 0, 16, 2.75, 19.97, 'a', 'a', 'a', NULL, 3)");
            assertThatThrownBy(() -> sql(c, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) "
                    + "VALUES (31, 'CARD', 20.00, 'PAID', 'a')")).hasMessageContaining("PAYMENT_AMOUNT_MISMATCH");
            assertThatThrownBy(() -> sql(c, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) "
                    + "VALUES (31, 'CASH', 19.97, 'PAID', 'a')")).hasMessageContaining("PAYMENT_AMOUNT_MISMATCH");
            assertThatThrownBy(() -> sql(c, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at, "
                    + "tendered_minor, change_minor) VALUES (31, 'CASH', 20.00, 'PAID', 'a', 5000, 2000)"))
                    .hasMessageContaining("CHECK");
            sql(c, SALE + "(32, 1, 5, 'INV-000032', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', NULL, 0)");
            assertThatThrownBy(() -> sql(c, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) "
                    + "VALUES (32, 'BITCOIN', 11.6, 'PAID', 'a')")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql(c, "UPDATE payments SET amount = 1 WHERE sale_id = 30"))
                    .hasMessageContaining("PAYMENT_NO_UPDATE");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET rounding_minor = 0 WHERE sale_id = 30"))
                    .hasMessageContaining("SALE_ROUNDING_FIXED");

            // cheque: for a CHEQUE payment of that sale, full amount, RECEIVED; number unique per bank
            sql(c, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (32, 'CHEQUE', 11.6, 'PAID', 'a')");
            long pay = n(c, "SELECT payment_id FROM payments WHERE sale_id = 32");
            assertThatThrownBy(() -> sql(c, cheque(9, 32, "100", "Bank of Palestine", 1100, "RECEIVED")))
                    .hasMessageContaining("CHEQUE_BAD_INSERT"); // wrong payment
            assertThatThrownBy(() -> sql(c, cheque(pay, 32, "100", "Bank of Palestine", 1000, "RECEIVED")))
                    .hasMessageContaining("CHEQUE_BAD_INSERT"); // wrong amount
            assertThatThrownBy(() -> sql(c, cheque(pay, 32, "100", "Bank of Palestine", 1160, "CLEARED")))
                    .hasMessageContaining("CHEQUE_BAD_INSERT"); // never cleared at sale time
            assertThatThrownBy(() -> sql(c, cheque(pay, 32, "100", "Bank", 1160, "RECEIVED").replace("'2026-12-31'", "'31/12/2026'")))
                    .hasMessageContaining("CHECK");
            sql(c, cheque(pay, 32, "100", "Bank of Palestine", 1160, "RECEIVED"));
            sql(c, SALE + "(33, 1, 5, 'INV-000033', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', NULL, 0)",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (33, 'CHEQUE', 11.6, 'PAID', 'a')");
            long pay2 = n(c, "SELECT payment_id FROM payments WHERE sale_id = 33");
            assertThatThrownBy(() -> sql(c, cheque(pay2, 33, "100", "BANK OF PALESTINE", 1160, "RECEIVED")))
                    .hasMessageContaining("UNIQUE"); // same cheque twice
            // a suspended sale cannot carry a cheque
            sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, "
                    + "total, created_at, updated_at) VALUES (34, 1, 5, 'INV-000034', 'SUSPENDED', 10, 0, 0, 0, 10, 'a', 'a')");
            assertThatThrownBy(() -> sql(c, cheque(pay2, 34, "101", "Bank", 1000, "RECEIVED"))).hasMessageContaining("CHEQUE_BAD_INSERT");

            long chq = n(c, "SELECT cheque_id FROM cheques WHERE sale_id = 32");
            assertThatThrownBy(() -> sql(c, event(chq, "RECEIVED", "CLEARED", 1, 4, "2026-11-28T10:00", null)))
                    .hasMessageContaining("CHEQUE_EVENT_INVALID"); // no skipping
            sql(c, event(chq, "RECEIVED", "DEPOSITED", 1, 4, "2026-11-28T10:00", null),
                    event(chq, "DEPOSITED", "CLEARED", 2, 4, "2026-11-29T10:00", null));
            assertThat(one(c, "SELECT status || '/' || version FROM cheques WHERE cheque_id = " + chq)).isEqualTo("CLEARED/2");
            assertThatThrownBy(() -> sql(c, event(chq, "CLEARED", "RECEIVED", 3, 4, "2026-11-30T10:00", null)))
                    .hasMessageContaining("CHEQUE_EVENT_INVALID"); // CLEARED is final
            assertThatThrownBy(() -> sql(c, "DELETE FROM cheques WHERE cheque_id = " + chq)).hasMessageContaining("CHEQUE_NO_DELETE");
            // another store cannot point at this store's sale (composite key)
            assertThatThrownBy(() -> sql(c, "INSERT INTO cheques (store_id, sale_id, payment_id, cheque_number, bank_name, "
                    + "amount_minor, due_date, status, created_by, created_at, updated_at) VALUES (2, 33, " + pay2
                    + ", '7', 'Bank', 1160, '2026-12-31', 'RECEIVED', 6, '2026-11-28T09:00', '2026-11-28T09:00')"))
                    .hasMessageContaining("FOREIGN KEY");
        }
    }

    private static String cheque(long paymentId, long saleId, String number, String bank, long amountMinor, String status) {
        return "INSERT INTO cheques (store_id, sale_id, payment_id, cheque_number, bank_name, amount_minor, due_date, status, "
                + "created_by, created_at, updated_at) VALUES (1, " + saleId + ", " + paymentId + ", '" + number + "', '" + bank
                + "', " + amountMinor + ", '2026-12-31', '" + status + "', 5, '2026-11-28T09:00:00', '2026-11-28T09:00:00')";
    }

    /** A status change of a cheque: ONLY by recording its event (the database applies it). */
    private static String event(long chequeId, String from, String to, int toVersion, int userId, String at, String note) {
        return "INSERT INTO cheque_events (cheque_id, store_id, from_status, to_status, to_version, user_id, created_at, note) "
                + "VALUES (" + chequeId + ", 1, " + (from == null ? "NULL" : "'" + from + "'") + ", '" + to + "', " + toVersion
                + ", " + userId + ", '" + at + "', " + (note == null ? "NULL" : "'" + note + "'") + ")";
    }

    /** A completed store-1 sale of 2 × Milk paid by CHEQUE for totalMinor; returns the cheque id. */
    private static long chequeSale(Connection c, int saleId, long totalMinor, String number) throws SQLException {
        double total = totalMinor / 100.0;
        sql(c, SALE + "(" + saleId + ", 1, 5, 'INV-0000" + saleId + "', 'COMPLETED', " + total + ", 0, 0, 0, " + total
                        + ", 'a', 'a', 'a', NULL, 0)",
                "INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price, line_total) "
                        + "VALUES (" + saleId + ", " + saleId + ", 2, 'Milk', '22222222', 2, " + (total / 2) + ", " + total + ")",
                "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (" + saleId
                        + ", 'CHEQUE', " + total + ", 'PAID', 'a')");
        long pay = n(c, "SELECT payment_id FROM payments WHERE sale_id = " + saleId);
        sql(c, cheque(pay, saleId, number, "Arab Bank", totalMinor, "RECEIVED"));
        return n(c, "SELECT cheque_id FROM cheques WHERE sale_id = " + saleId);
    }

    /** A Store Admin return of 1 unit of a cheque sale (refund owed, no cash now); returns the return id. */
    private static long chequeReturn(Connection c, int saleId, long refundMinor) throws SQLException {
        sql(c, "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                + "refund_method, return_date, refund_minor) VALUES (1, " + saleId + ", " + saleId + ", 2, 4, 1, 'Damaged', "
                + (refundMinor / 100.0) + ", 'CHEQUE', 'a', " + refundMinor + ")");
        return n(c, "SELECT max(return_id) FROM returns");
    }

    private static void clear(Connection c, long chq) throws SQLException {
        sql(c, event(chq, "RECEIVED", "DEPOSITED", 1, 4, "2026-11-28T10:00", null),
                event(chq, "DEPOSITED", "CLEARED", 2, 4, "2026-11-29T10:00", null));
    }

    private static void openShift(Connection c) throws SQLException {
        sql(c, "INSERT INTO cash_drawers (store_id, name, created_at) VALUES (1, 'Main', 'a')",
                "INSERT INTO cash_shifts (store_id, drawer_id, cashier_id, status, opened_at, opening_cash_minor) "
                        + "VALUES (1, 1, 5, 'OPEN', 'a', 50000)");
    }

    // ── Reported defect 3: every committed cheque status change has its exact audit event ──

    @Test
    void defect3_chequeStatus_onlyThroughItsEvent_completeAccurateHistory() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            long chq = chequeSale(c, 40, 1000, "555");
            // the RECEIVED event was written by the database with the cheque: actor, store, time, version 0
            assertThat(one(c, "SELECT coalesce(from_status, 'null') || '>' || to_status || '/' || to_version || '/' || user_id "
                    + "|| '/' || store_id || '/' || created_at FROM cheque_events WHERE cheque_id = " + chq))
                    .isEqualTo("null>RECEIVED/0/5/1/2026-11-28T09:00:00");
            // BEFORE the fix these succeeded: a transition with no event at all
            assertThatThrownBy(() -> sql(c, "UPDATE cheques SET status = 'DEPOSITED', version = 1, updated_at = 'b' WHERE cheque_id = " + chq))
                    .hasMessageContaining("CHEQUE_UPDATE_WITHOUT_EVENT");
            assertThatThrownBy(() -> sql(c, "UPDATE cheques SET status = 'CLEARED', version = 1, updated_at = 'c' WHERE cheque_id = " + chq))
                    .hasMessageContaining("CHEQUE_UPDATE_WITHOUT_EVENT");
            assertThatThrownBy(() -> sql(c, "UPDATE cheques SET status = 'CLEARED', version = 2, updated_at = 'c' WHERE cheque_id = " + chq))
                    .hasMessageContaining("CHEQUE_IDENTITY"); // version jump
            // forged / wrong events are refused
            assertThatThrownBy(() -> sql(c, event(chq, null, "RECEIVED", 0, 5, "2026-11-28T09:00:00", null)))
                    .hasMessageContaining("UNIQUE"); // a second "initial" event
            assertThatThrownBy(() -> sql(c, event(chq, "RECEIVED", "DEPOSITED", 1, 5, "2026-11-28T10:00", null)))
                    .hasMessageContaining("CHEQUE_EVENT_INVALID"); // a cashier
            assertThatThrownBy(() -> sql(c, event(chq, "DEPOSITED", "CLEARED", 1, 4, "2026-11-28T10:00", null)))
                    .hasMessageContaining("CHEQUE_EVENT_INVALID"); // wrong previous status
            assertThatThrownBy(() -> sql(c, event(chq, "RECEIVED", "DEPOSITED", 5, 4, "2026-11-28T10:00", null)))
                    .hasMessageContaining("CHEQUE_EVENT_INVALID"); // wrong version
            assertThatThrownBy(() -> sql(c, event(chq, "RECEIVED", "DEPOSITED", 1, 8, "2026-11-28T10:00", null)))
                    .hasMessageContaining("FOREIGN KEY"); // Store Admin of ANOTHER store
            assertThatThrownBy(() -> sql(c, "INSERT INTO cheque_events (cheque_id, store_id, from_status, to_status, to_version, "
                    + "user_id, created_at) VALUES (" + chq + ", 2, 'RECEIVED', 'DEPOSITED', 1, 8, '2026-11-28T10:00')"))
                    .hasMessageContaining("FOREIGN KEY"); // event filed under another store
            // atomic: event + status in one statement; a rolled-back transaction leaves neither
            c.setAutoCommit(false);
            sql(c, event(chq, "RECEIVED", "DEPOSITED", 1, 4, "2026-11-28T10:00", null));
            assertThat(one(c, "SELECT status FROM cheques WHERE cheque_id = " + chq)).isEqualTo("DEPOSITED");
            c.rollback();
            c.setAutoCommit(true);
            assertThat(one(c, "SELECT status || '/' || version FROM cheques WHERE cheque_id = " + chq)).isEqualTo("RECEIVED/0");
            assertThat(n(c, "SELECT COUNT(*) FROM cheque_events WHERE cheque_id = " + chq)).isEqualTo(1);
            // real path: DEPOSITED, BOUNCED (note required), DEPOSITED again, CLEARED
            sql(c, event(chq, "RECEIVED", "DEPOSITED", 1, 4, "2026-11-28T10:00", null));
            assertThatThrownBy(() -> sql(c, event(chq, "DEPOSITED", "BOUNCED", 2, 4, "2026-11-29T10:00", null)))
                    .hasMessageContaining("CHECK");
            sql(c, event(chq, "DEPOSITED", "BOUNCED", 2, 4, "2026-11-29T10:00", "Insufficient funds"),
                    event(chq, "BOUNCED", "DEPOSITED", 3, 4, "2026-11-30T10:00", null),
                    event(chq, "DEPOSITED", "CLEARED", 4, 4, "2026-12-01T10:00", null));
            assertThat(one(c, "SELECT status || '/' || version || '/' || updated_at FROM cheques WHERE cheque_id = " + chq))
                    .isEqualTo("CLEARED/4/2026-12-01T10:00");
            // the history is a complete unbroken chain: version+1 events, each from = previous to
            assertThat(n(c, "SELECT COUNT(*) FROM cheque_events WHERE cheque_id = " + chq)).isEqualTo(5);
            assertThat(n(c, "SELECT COUNT(*) FROM cheque_events e JOIN cheque_events p ON p.cheque_id = e.cheque_id "
                    + "AND p.to_version = e.to_version - 1 WHERE e.cheque_id = " + chq + " AND e.from_status IS NOT p.to_status")).isZero();
            assertThat(one(c, "SELECT group_concat(to_status, '>') FROM (SELECT to_status FROM cheque_events WHERE cheque_id = "
                    + chq + " ORDER BY to_version)")).isEqualTo("RECEIVED>DEPOSITED>BOUNCED>DEPOSITED>CLEARED");
            // no edits to the cheque itself, no edits / deletes of history
            assertThatThrownBy(() -> sql(c, "UPDATE cheques SET notes = 'x', version = 5, updated_at = 'z' WHERE cheque_id = " + chq))
                    .hasMessageMatching("(?s).*(CHEQUE_IDENTITY|CHEQUE_UPDATE_WITHOUT_EVENT).*"); // notes are fixed
            assertThatThrownBy(() -> sql(c, "UPDATE cheque_events SET note = 'x'")).hasMessageContaining("APPEND_ONLY");
            assertThatThrownBy(() -> sql(c, "DELETE FROM cheque_events")).hasMessageContaining("APPEND_ONLY");
        }
    }

    // ── Reported defect 1: a cheque refund belongs to the return AND the cheque of the SAME sale ──

    @Test
    void defect1_chequeRefund_sameSale_sameStore_exactAmount_noDuplicateOrExcess() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            long chq = chequeSale(c, 40, 1000, "555");
            // the CASH sale 1 already has a return (return_id 1): BEFORE the fix it could be linked to cheque 40.
            // Refused by the insert trigger (first) and by the composite foreign keys (proved alone below).
            assertThatThrownBy(() -> sql(c, "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, "
                    + "status, created_at) VALUES (1, 40, 1, " + chq + ", 500, 'PENDING', 'a')")).hasMessageContaining("CHEQUE_REFUND_BAD_INSERT");
            assertThatThrownBy(() -> sql(c, "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, "
                    + "status, created_at) VALUES (1, 1, 1, " + chq + ", 500, 'PENDING', 'a')")).hasMessageContaining("CHEQUE_REFUND_BAD_INSERT");
            // returns of a cheque sale: Store Admin only, CHEQUE method only (no cash now), exact amount
            String ret = "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                    + "refund_amount, refund_method, return_date, refund_minor) VALUES (1, 40, 40, 2, %d, 1, 'x', 5, '%s', 'a', %s)";
            assertThatThrownBy(() -> sql(c, ret.formatted(5, "CHEQUE", "500"))).hasMessageContaining("CHEQUE_RETURN_NEEDS_STORE_ADMIN");
            assertThatThrownBy(() -> sql(c, ret.formatted(4, "CASH", "500"))).hasMessageContaining("RETURN_AMOUNT_INVALID");
            assertThatThrownBy(() -> sql(c, ret.formatted(4, "CHEQUE", "NULL"))).hasMessageContaining("CHEQUE_RETURN_NEEDS_STORE_ADMIN");
            assertThatThrownBy(() -> sql(c, ret.formatted(4, "CHEQUE", "400"))).hasMessageContaining("RETURN_AMOUNT_INVALID");
            long r1 = chequeReturn(c, 40, 500);
            String refund = "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, status, created_at) "
                    + "VALUES (%d, %d, %d, %d, %d, '%s', 'a')";
            assertThatThrownBy(() -> sql(c, refund.formatted(1, 40, r1, chq, 600, "PENDING"))).hasMessageContaining("CHEQUE_REFUND_BAD_INSERT");
            assertThatThrownBy(() -> sql(c, refund.formatted(1, 40, r1, chq, 500, "PAID"))).hasMessageContaining("CHEQUE_REFUND_BAD_INSERT");
            assertThatThrownBy(() -> sql(c, refund.formatted(2, 40, r1, chq, 500, "PENDING"))).hasMessageContaining("FOREIGN KEY");
            sql(c, refund.formatted(1, 40, r1, chq, 500, "PENDING"));
            assertThatThrownBy(() -> sql(c, refund.formatted(1, 40, r1, chq, 500, "PENDING"))).hasMessageContaining("UNIQUE"); // duplicate
            // a cheque of ANOTHER cheque sale cannot be used for this return
            long chq2 = chequeSale(c, 41, 1000, "556");
            long r2 = chequeReturn(c, 40, 500);
            assertThatThrownBy(() -> sql(c, refund.formatted(1, 40, r2, chq2, 500, "PENDING"))).hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql(c, refund.formatted(1, 41, r2, chq2, 500, "PENDING"))).hasMessageContaining("FOREIGN KEY");
            sql(c, refund.formatted(1, 40, r2, chq, 500, "PENDING"));
            // never more than the customer paid: a third return of this 10.00 sale is refused
            assertThatThrownBy(() -> chequeReturn(c, 40, 100)).hasMessageContaining("RETURN_AMOUNT_INVALID");
            assertThat(n(c, "SELECT sum(amount_minor) FROM cheque_refunds WHERE cheque_id = " + chq)).isEqualTo(1000);

            // defence in depth: even WITHOUT the insert trigger (e.g. a buggy future change dropped it), the
            // composite foreign keys alone refuse a return and a cheque of different sales
            sql(c, "DROP TRIGGER trg_cheque_refund_insert");
            assertThatThrownBy(() -> sql(c, "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, "
                    + "status, created_at) VALUES (1, 40, 1, " + chq + ", 500, 'PENDING', 'a')")).hasMessageContaining("FOREIGN KEY");
            assertThatThrownBy(() -> sql(c, "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, "
                    + "status, created_at) VALUES (1, 1, 1, " + chq + ", 500, 'PENDING', 'a')")).hasMessageContaining("FOREIGN KEY");
        }
    }

    // ── Reported defect 2: a cash payout of a cheque refund and its drawer movement are one unit ──

    @Test
    void defect2_cashPayout_exactlyOneMovement_atomic_retrySafe_bankTransferHasNone() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            long chq = chequeSale(c, 40, 1000, "555");
            long r1 = chequeReturn(c, 40, 500), r2 = chequeReturn(c, 40, 300);
            sql(c, "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, status, created_at) VALUES "
                    + "(1, 40, " + r1 + ", " + chq + ", 500, 'PENDING', 'a'), (1, 40, " + r2 + ", " + chq + ", 300, 'PENDING', 'a')");
            long cr1 = n(c, "SELECT cheque_refund_id FROM cheque_refunds WHERE return_id = " + r1);
            long cr2 = n(c, "SELECT cheque_refund_id FROM cheque_refunds WHERE return_id = " + r2);
            clear(c, chq);
            openShift(c);
            String paidCash = "UPDATE cheque_refunds SET status = 'PAID', paid_method = 'CASH', decided_by = 4, decided_at = 'd', "
                    + "payout_movement_id = %s, version = version + 1 WHERE cheque_refund_id = %d";
            String movement = "INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, cheque_refund_id, "
                    + "created_by, created_at, request_key) VALUES (1, 1, 'CHEQUE_REFUND_OUT', %d, 'Refund INV-000040', %d, %d, 'c', '%s')";

            // BEFORE the fix this succeeded: PAID in cash with no movement
            assertThatThrownBy(() -> sql(c, paidCash.formatted("NULL", cr1))).hasMessageContaining("CHEQUE_REFUND_BAD_DECISION");
            assertThatThrownBy(() -> sql(c, paidCash.formatted("999", cr1))).hasMessageContaining("CHEQUE_REFUND_BAD_DECISION");
            // a movement alone cannot be committed (autocommit = its own transaction)
            assertThatThrownBy(() -> sql(c, movement.formatted(500, cr1, 4, "req-0000001"))).hasMessageContaining("FOREIGN KEY");
            assertThat(n(c, "SELECT COUNT(*) FROM cash_movements")).isZero();
            // a transaction with the movement but without marking the refund PAID cannot COMMIT
            c.setAutoCommit(false);
            sql(c, movement.formatted(500, cr1, 4, "req-0000001"));
            assertThatThrownBy(c::commit).hasMessageContaining("FOREIGN KEY");
            c.rollback();
            c.setAutoCommit(true);
            assertThat(n(c, "SELECT COUNT(*) FROM cash_movements")).isZero();
            // a failure between the two steps rolls BOTH back
            c.setAutoCommit(false);
            sql(c, movement.formatted(500, cr1, 4, "req-0000001"));
            long m = n(c, "SELECT last_insert_rowid()");
            sql(c, paidCash.formatted(String.valueOf(m), cr1));
            c.rollback(); // e.g. the service throws after the update
            c.setAutoCommit(true);
            assertThat(one(c, "SELECT status FROM cheque_refunds WHERE cheque_refund_id = " + cr1)).isEqualTo("PENDING");
            assertThat(n(c, "SELECT COUNT(*) FROM cash_movements")).isZero();
            // wrong amount / a cashier / a movement of another refund: refused
            assertThatThrownBy(() -> sql(c, movement.formatted(400, cr1, 4, "req-0000002"))).hasMessageContaining("CASH_MOVEMENT_NOT_ALLOWED");
            assertThatThrownBy(() -> sql(c, movement.formatted(500, cr1, 5, "req-0000002"))).hasMessageContaining("CASH_MOVEMENT_NOT_ALLOWED");
            // the correct payout: movement + refund PAID in one transaction, committed together
            c.setAutoCommit(false);
            sql(c, movement.formatted(500, cr1, 4, "req-0000001"));
            long paid = n(c, "SELECT last_insert_rowid()");
            sql(c, paidCash.formatted(String.valueOf(paid), cr1));
            c.commit();
            c.setAutoCommit(true);
            assertThat(one(c, "SELECT status || '/' || paid_method || '/' || payout_movement_id FROM cheque_refunds "
                    + "WHERE cheque_refund_id = " + cr1)).isEqualTo("PAID/CASH/" + paid);
            assertThat(one(c, "SELECT shift_id || '/' || store_id || '/' || amount_minor || '/' || cheque_refund_id FROM cash_movements"))
                    .isEqualTo("1/1/500/" + cr1);
            // retries: same request key, or a second payout of the same refund — refused, still ONE movement
            // (refused already by the trigger — the refund is no longer PENDING — and by UNIQUE behind it)
            assertThatThrownBy(() -> sql(c, movement.formatted(500, cr1, 4, "req-0000001"))).hasMessageContaining("CASH_MOVEMENT_NOT_ALLOWED");
            assertThatThrownBy(() -> sql(c, movement.formatted(500, cr1, 4, "req-0000009"))).hasMessageContaining("CASH_MOVEMENT_NOT_ALLOWED");
            assertThatThrownBy(() -> sql(c, paidCash.formatted(String.valueOf(paid), cr1))).hasMessageContaining("CHEQUE_REFUND_BAD_DECISION");
            assertThat(n(c, "SELECT COUNT(*) FROM cash_movements")).isEqualTo(1);
            // bank transfer: no drawer movement at all; a movement for it can never be committed
            c.setAutoCommit(false);
            sql(c, movement.formatted(300, cr2, 4, "req-0000003"));
            assertThatThrownBy(() -> sql(c, "UPDATE cheque_refunds SET status = 'PAID', paid_method = 'BANK_TRANSFER', decided_by = 4, "
                    + "decided_at = 'd', version = version + 1 WHERE cheque_refund_id = " + cr2)).hasMessageContaining("CHEQUE_REFUND_BAD_DECISION");
            c.rollback();
            c.setAutoCommit(true);
            sql(c, "UPDATE cheque_refunds SET status = 'PAID', paid_method = 'BANK_TRANSFER', decided_by = 4, decided_at = 'd', "
                    + "version = version + 1 WHERE cheque_refund_id = " + cr2);
            assertThat(n(c, "SELECT COUNT(*) FROM cash_movements WHERE cheque_refund_id = " + cr2)).isZero();
            // a PENDING refund can be CANCELLED with a reason (no paid_method: NULL must not block it —
            // found by the API tests: the first V7 draft refused every cancellation)
            long r3 = chequeReturn(c, 40, 100);
            sql(c, "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, status, created_at) VALUES "
                    + "(1, 40, " + r3 + ", " + chq + ", 100, 'PENDING', 'a')");
            long cr3 = n(c, "SELECT cheque_refund_id FROM cheque_refunds WHERE return_id = " + r3);
            assertThatThrownBy(() -> sql(c, "UPDATE cheque_refunds SET status = 'CANCELLED', decided_by = 4, decided_at = 'd', "
                    + "version = version + 1 WHERE cheque_refund_id = " + cr3)).hasMessageContaining("CHECK"); // a reason is required
            sql(c, "UPDATE cheque_refunds SET status = 'CANCELLED', decided_by = 4, decided_at = 'd', reason = 'Goods kept', "
                    + "version = version + 1 WHERE cheque_refund_id = " + cr3);
            assertThatThrownBy(() -> sql(c, "UPDATE cheque_refunds SET status = 'CANCELLED', decided_by = 5, decided_at = 'd', "
                    + "reason = 'Not me', version = version + 1 WHERE cheque_refund_id = " + cr3))
                    .hasMessageContaining("CHEQUE_REFUND_BAD_DECISION"); // decided once
            assertThat(n(c, "SELECT COUNT(*) FROM cash_movements WHERE cheque_refund_id = " + cr3)).isZero();
            // the drawer: 500.00 opening − 5.00 cash payout (the bank transfer is not in the drawer)
            assertThat(one(c, EXPECTED.formatted(1))).isEqualTo("49500");
        }
    }

    /**
     * The Spring side of defect 2: the payout service will run movement + refund in ONE @Transactional
     * method. Here the same Spring transaction manager over SQLite: a payout that "forgets" the refund
     * update fails at COMMIT (deferred foreign keys) and Spring rolls back; a complete one commits.
     */
    @Test
    void defect2_springTransaction_incompletePayoutFailsAtCommit_completeOneCommits() throws Exception {
        fillV6();
        migrate();
        long cr;
        try (Connection c = open()) {
            long chq = chequeSale(c, 40, 1000, "555");
            long r = chequeReturn(c, 40, 500);
            sql(c, "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, status, created_at) "
                    + "VALUES (1, 40, " + r + ", " + chq + ", 500, 'PENDING', 'a')");
            cr = n(c, "SELECT max(cheque_refund_id) FROM cheque_refunds");
            clear(c, chq);
            openShift(c);
        }
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(url());
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        String insertMovement = "INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, cheque_refund_id, "
                + "created_by, created_at, request_key) VALUES (1, 1, 'CHEQUE_REFUND_OUT', 500, 'Refund', ?, 4, 'c', ?)";
        // a buggy service: movement only
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(insertMovement, cr, "req-S0000001")))
                .hasStackTraceContaining("FOREIGN KEY");
        // a service that throws after both writes (e.g. a later validation): everything rolled back
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.update(insertMovement, cr, "req-S0000002");
            Long m = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            jdbc.update("UPDATE cheque_refunds SET status = 'PAID', paid_method = 'CASH', decided_by = 4, decided_at = 'd', "
                    + "payout_movement_id = ?, version = version + 1 WHERE cheque_refund_id = ?", m, cr);
            throw new IllegalStateException("receipt printing failed");
        })).hasMessageContaining("receipt printing failed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movements", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM cheque_refunds WHERE cheque_refund_id = ?", String.class, cr))
                .isEqualTo("PENDING");
        // the correct service method: both writes, one commit
        tx.executeWithoutResult(s -> {
            jdbc.update(insertMovement, cr, "req-S0000003");
            Long m = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            jdbc.update("UPDATE cheque_refunds SET status = 'PAID', paid_method = 'CASH', decided_by = 4, decided_at = 'd', "
                    + "payout_movement_id = ?, version = version + 1 WHERE cheque_refund_id = ?", m, cr);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cash_movements", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM cheque_refunds WHERE cheque_refund_id = ?", String.class, cr))
                .isEqualTo("PAID");
    }

    @Test
    void defect2_concurrentPayouts_onlyOneSucceeds() throws Exception {
        fillV6();
        migrate();
        long cr;
        try (Connection c = open()) {
            long chq = chequeSale(c, 40, 1000, "555");
            long r = chequeReturn(c, 40, 500);
            sql(c, "INSERT INTO cheque_refunds (store_id, sale_id, return_id, cheque_id, amount_minor, status, created_at) "
                    + "VALUES (1, 40, " + r + ", " + chq + ", 500, 'PENDING', 'a')");
            cr = n(c, "SELECT max(cheque_refund_id) FROM cheque_refunds");
            clear(c, chq);
            openShift(c);
        }
        try (Connection a = open(); Connection b = open()) {
            sql(b, "PRAGMA busy_timeout = 100");
            // A starts the payout and holds the write lock
            sql(a, "BEGIN IMMEDIATE",
                    "INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, cheque_refund_id, created_by, "
                            + "created_at, request_key) VALUES (1, 1, 'CHEQUE_REFUND_OUT', 500, 'Refund', " + cr + ", 4, 'c', 'req-A0000001')");
            long ma = n(a, "SELECT last_insert_rowid()");
            sql(a, "UPDATE cheque_refunds SET status = 'PAID', paid_method = 'CASH', decided_by = 4, decided_at = 'd', "
                    + "payout_movement_id = " + ma + ", version = version + 1 WHERE cheque_refund_id = " + cr);
            // B (a second click / another admin) cannot even start writing meanwhile
            assertThatThrownBy(() -> sql(b, "BEGIN IMMEDIATE")).hasMessageContaining("BUSY");
            sql(a, "COMMIT");
            // after A, B's payout of the same refund is refused
            sql(b, "BEGIN IMMEDIATE");
            assertThatThrownBy(() -> sql(b, "INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, "
                    + "cheque_refund_id, created_by, created_at, request_key) VALUES (1, 1, 'CHEQUE_REFUND_OUT', 500, 'Refund', "
                    + cr + ", 4, 'c', 'req-B0000001')")).hasMessageMatching("(?s).*(UNIQUE|CASH_MOVEMENT_NOT_ALLOWED).*");
            sql(b, "ROLLBACK");
            assertThat(n(b, "SELECT COUNT(*) FROM cash_movements")).isEqualTo(1);
        }
    }

    // ── Shifts, cash movements, closing ────────────────────────────────────────────

    /** The database's expected-cash formula (same as trg_shift_expected), for shift %d. */
    private static final String EXPECTED = """
            SELECT s.opening_cash_minor
              + coalesce((SELECT sum(CAST(round(p.amount * 100) AS INTEGER)) FROM payments p JOIN sales x ON x.sale_id = p.sale_id
                          WHERE x.shift_id = s.shift_id AND p.payment_method = 'CASH'), 0)
              - coalesce((SELECT sum(coalesce(r.refund_minor, CAST(round(r.refund_amount * 100) AS INTEGER)))
                          FROM returns r WHERE r.shift_id = s.shift_id AND r.refund_method = 'CASH'), 0)
              + coalesce((SELECT sum(amount_minor) FROM cash_movements m WHERE m.shift_id = s.shift_id AND m.type = 'CASH_IN'), 0)
              - coalesce((SELECT sum(amount_minor) FROM cash_movements m WHERE m.shift_id = s.shift_id
                          AND m.type IN ('CASH_OUT', 'CHEQUE_REFUND_OUT')), 0)
            FROM cash_shifts s WHERE s.shift_id = %d""";

    @Test
    void shifts_oneOpenPerCashierAndPerDrawer_closedFrozen_salesAndMovementsNeedAnOpenShift() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            sql(c, "INSERT INTO cash_drawers (store_id, name, created_at) VALUES (1, 'Drawer 1', 'a'), (1, 'Drawer 2', 'a'), "
                    + "(2, 'Drawer 1', 'a')");
            String open = "INSERT INTO cash_shifts (store_id, drawer_id, cashier_id, status, opened_at, opening_cash_minor) "
                    + "VALUES (%d, %d, %d, 'OPEN', 'a', 50000)";
            sql(c, open.formatted(1, 1, 5));
            assertThatThrownBy(() -> sql(c, open.formatted(1, 2, 5))).hasMessageContaining("UNIQUE"); // same cashier
            assertThatThrownBy(() -> sql(c, open.formatted(1, 1, 7))).hasMessageContaining("UNIQUE"); // same drawer
            assertThatThrownBy(() -> sql(c, open.formatted(1, 3, 7))).hasMessageContaining("FOREIGN KEY"); // other store's drawer
            assertThatThrownBy(() -> sql(c, open.formatted(1, 2, 6))).hasMessageContaining("FOREIGN KEY"); // other store's cashier
            sql(c, open.formatted(1, 2, 7), open.formatted(2, 3, 6));

            sql(c, SALE + "(50, 1, 5, 'INV-000050', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', 1, 0)",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (50, 'CASH', 11.6, 'PAID', 'a')");
            assertThatThrownBy(() -> sql(c, SALE + "(51, 1, 5, 'INV-000051', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', 3, 0)"))
                    .hasMessageContaining("SALE_SHIFT_NOT_OPEN");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET shift_id = 2 WHERE sale_id = 50"))
                    .hasMessageContaining("SALE_SHIFT_NOT_OPEN");

            String mv = "INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, created_by, created_at, "
                    + "request_key) VALUES (1, 1, '%s', %d, '%s', %d, 'a', '%s')";
            assertThatThrownBy(() -> sql(c, mv.formatted("CASH_IN", 2000, "Change float", 5, "k-0000001")))
                    .hasMessageContaining("CASH_MOVEMENT_NOT_ALLOWED");
            assertThatThrownBy(() -> sql(c, mv.formatted("CASH_IN", 2000, "  ", 4, "k-0000001"))).hasMessageContaining("CHECK");
            sql(c, mv.formatted("CASH_IN", 2000, "Change float", 4, "k-0000001"),
                    mv.formatted("CASH_OUT", 500, "Bank deposit", 4, "k-0000002"));
            assertThatThrownBy(() -> sql(c, mv.formatted("CASH_IN", 2000, "Change float", 4, "k-0000001")))
                    .hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> sql(c, "DELETE FROM cash_movements")).hasMessageContaining("APPEND_ONLY");

            // expected = 500.00 + 11.60 + 20.00 − 5.00 = 526.60 — computed by the database, not trusted from the caller
            assertThat(one(c, EXPECTED.formatted(1))).isEqualTo("52660");
            String close = "UPDATE cash_shifts SET status = 'CLOSED', closed_at = 'b', closed_by = %d, close_kind = '%s', "
                    + "close_reason = %s, expected_cash_minor = %d, counted_cash_minor = %d, difference_minor = %d, "
                    + "closing_note = %s, version = version + 1 WHERE shift_id = 1";
            assertThatThrownBy(() -> sql(c, close.formatted(5, "CASHIER", "NULL", 52000, 52000, 0, "NULL")))
                    .hasMessageContaining("SHIFT_EXPECTED_MISMATCH"); // a wrong expected amount
            assertThatThrownBy(() -> sql(c, close.formatted(5, "CASHIER", "NULL", 52660, 52460, -200, "NULL")))
                    .hasMessageContaining("CHECK"); // a shortage needs a note
            assertThatThrownBy(() -> sql(c, close.formatted(5, "CASHIER", "NULL", 52660, 52460, 0, "'ok'")))
                    .hasMessageContaining("CHECK"); // difference must be counted − expected
            assertThatThrownBy(() -> sql(c, close.formatted(7, "CASHIER", "NULL", 52660, 52460, -200, "'Short by 2'")))
                    .hasMessageContaining("SHIFT_CLOSER_NOT_ALLOWED");
            sql(c, close.formatted(5, "CASHIER", "NULL", 52660, 52460, -200, "'Short by 2'"));
            assertThatThrownBy(() -> sql(c, close.formatted(5, "CASHIER", "NULL", 52660, 52460, -200, "'again'")))
                    .hasMessageContaining("SHIFT_CLOSED");
            assertThatThrownBy(() -> sql(c, SALE + "(52, 1, 5, 'INV-000052', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', 1, 0)"))
                    .hasMessageContaining("SALE_SHIFT_NOT_OPEN");
            assertThatThrownBy(() -> sql(c, mv.formatted("CASH_IN", 100, "Late", 4, "k-0000003")))
                    .hasMessageContaining("CASH_MOVEMENT_NOT_ALLOWED");
            assertThatThrownBy(() -> sql(c, "DELETE FROM cash_shifts WHERE shift_id = 1")).hasMessageContaining("SHIFT_NO_DELETE");

            String admin = "UPDATE cash_shifts SET status = 'CLOSED', closed_at = 'b', closed_by = %d, close_kind = 'ADMIN_EXCEPTION', "
                    + "close_reason = %s, expected_cash_minor = 50000, counted_cash_minor = 50000, difference_minor = 0, "
                    + "version = version + 1 WHERE shift_id = 2";
            assertThatThrownBy(() -> sql(c, admin.formatted(4, "NULL"))).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql(c, admin.formatted(5, "'Cashier left early'"))).hasMessageContaining("SHIFT_CLOSER_NOT_ALLOWED");
            sql(c, admin.formatted(4, "'Cashier left early'"));
            sql(c, open.formatted(1, 1, 7)); // the drawer is free again (hand-over)

            // mandatory shifts (D2)
            sql(c, "UPDATE stores SET require_cash_shift = 1 WHERE store_id = 2");
            assertThatThrownBy(() -> sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, "
                    + "discount, tax_rate, tax, total, created_at, updated_at, completed_at) VALUES (60, 2, 6, 'INV-000060', "
                    + "'COMPLETED', 8, 0, 16, 1.28, 9.28, 'a', 'a', 'a')")).hasMessageContaining("SHIFT_REQUIRED");
            sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, "
                    + "total, created_at, updated_at) VALUES (61, 2, 6, 'INV-000061', 'SUSPENDED', 8, 0, 16, 1.28, 9.28, 'a', 'a')",
                    "INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price, line_total) "
                            + "VALUES (61, 61, 3, 'Juice', '33333333', 1, 8, 8)");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET status = 'COMPLETED' WHERE sale_id = 61"))
                    .hasMessageContaining("SHIFT_REQUIRED");
            sql(c, "UPDATE sales SET status = 'COMPLETED', shift_id = 3 WHERE sale_id = 61",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (61, 'CASH', 9.28, 'PAID', 'a')");
            String ret = "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                    + "refund_amount, refund_method, return_date, shift_id, refund_minor) VALUES (2, 61, 61, 3, %d, 1, 'x', 9.28, 'CASH', 'a', %s, 928)";
            assertThatThrownBy(() -> sql(c, ret.formatted(6, "NULL"))).hasMessageContaining("SHIFT_REQUIRED");
            assertThatThrownBy(() -> sql(c, ret.formatted(6, "1"))).hasMessageContaining("RETURN_SHIFT_NOT_OPEN"); // other store
            sql(c, ret.formatted(6, "3"));
            assertThat(one(c, EXPECTED.formatted(3))).isEqualTo("50000"); // +9.28 −9.28
            sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, "
                    + "total, created_at, updated_at, completed_at) VALUES (62, 1, 5, 'INV-000062', 'COMPLETED', 8, 0, 16, 1.28, "
                    + "9.28, 'a', 'a', 'a')"); // a store without mandatory shifts works as before
        }
    }

    @Test
    void closingRacesWithASale_theDatabaseRefusesAStaleExpectedAmount() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            openShift(c);
        }
        try (Connection a = open(); Connection b = open()) {
            sql(b, "PRAGMA busy_timeout = 100");
            String close = "UPDATE cash_shifts SET status = 'CLOSED', closed_at = 'b', closed_by = 5, close_kind = 'CASHIER', "
                    + "expected_cash_minor = %d, counted_cash_minor = %d, difference_minor = %d, closing_note = %s, "
                    + "version = version + 1 WHERE shift_id = 1";
            // A: a cash sale is being completed on the shift
            sql(a, "BEGIN IMMEDIATE", SALE + "(90, 1, 5, 'INV-000090', 'COMPLETED', 10, 0, 0, 0, 10, 'a', 'a', 'a', 1, 0)",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (90, 'CASH', 10, 'PAID', 'a')");
            // B: the cashier closes at the same moment with what B saw (500.00) — must wait
            assertThatThrownBy(() -> sql(b, close.formatted(50000, 50000, 0, "NULL"))).hasMessageContaining("BUSY");
            sql(a, "COMMIT");
            // B retries with the stale expected amount: refused — the sale cannot be silently missed
            assertThatThrownBy(() -> sql(b, close.formatted(50000, 50000, 0, "NULL"))).hasMessageContaining("SHIFT_EXPECTED_MISMATCH");
            sql(b, close.formatted(51000, 50000, -1000, "'10.00 short'"));
            assertThat(one(b, "SELECT status || '/' || expected_cash_minor || '/' || difference_minor FROM cash_shifts"))
                    .isEqualTo("CLOSED/51000/-1000");
        }
    }

    // ── the real Flyway path (what the application runs at start-up) ──

    @Test
    void flyway_populatedV6_migratesToV7_withVerifiedBackupNextToTheFile() throws Exception {
        fillV6();
        Map<String, List<String>> cols = new LinkedHashMap<>();
        Map<String, String> before;
        try (Connection c = open()) {
            for (String t : V7__store_operations.tables(c)) cols.put(t, V7__store_operations.columns(c, t));
            before = V7__store_operations.hashes(c, cols);
        }
        var result = Flyway.configure().dataSource(url(), null, null).locations("classpath:db/migration").load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("7");
        assertThat(result.migrationsExecuted).isEqualTo(1);
        List<Path> backups;
        try (Stream<Path> files = Files.list(dir)) {
            backups = files.filter(p -> p.getFileName().toString().startsWith(V7__store_operations.BACKUP_PREFIX)).toList();
        }
        assertThat(backups).hasSize(1);
        try (Connection b = DriverManager.getConnection("jdbc:sqlite:" + backups.get(0))) {
            assertThat(V7__store_operations.hashes(b, cols)).isEqualTo(before);
            assertThat(V7__store_operations.one(b, "SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history")).isEqualTo("6");
        }
        try (Connection c = open()) {
            assertThat(V7__store_operations.hashes(c, cols)).isEqualTo(before);
            assertThat(V7__store_operations.one(c, "PRAGMA integrity_check")).isEqualTo("ok");
            assertThat(n(c, "SELECT COUNT(*) FROM pragma_foreign_key_check")).isZero();
            for (String t : V7__store_operations.NEW_TABLES) assertThat(n(c, "SELECT COUNT(*) FROM " + t)).as(t).isZero();
        }
        // a second start does nothing
        assertThat(Flyway.configure().dataSource(url(), null, null).locations("classpath:db/migration").load().migrate()
                .migrationsExecuted).isZero();
    }

    @Test
    void flyway_newDatabase_V1toV7_noBackupNeeded() throws Exception {
        Path fresh = dir.resolve("fresh.db");
        var result = Flyway.configure().dataSource("jdbc:sqlite:" + fresh + "?foreign_keys=on", null, null)
                .locations("classpath:db/migration").load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("7");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith(V7__store_operations.BACKUP_PREFIX))).isEmpty();
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + fresh)) {
            for (String t : V7__store_operations.NEW_TABLES) {
                assertThat(n(c, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" + t + "'")).as(t).isOne();
            }
        }
    }

    /** Store 1: shift 1 (cashier 5) with a cash sale 10.00 and a cash return 5.00, then CLOSED. */
    private static void closedShiftWithHistory(Connection c) throws SQLException {
        openShift(c);
        sql(c, SALE + "(80, 1, 5, 'INV-000080', 'COMPLETED', 10, 0, 0, 0, 10, 'a', 'a', 'a', 1, 0)",
                "INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price, line_total) "
                        + "VALUES (80, 80, 2, 'Milk', '22222222', 2, 5, 10)",
                "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (80, 'CASH', 10, 'PAID', 'a')",
                "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                        + "refund_method, return_date, shift_id, refund_minor) VALUES (1, 80, 80, 2, 5, 1, 'x', 5, 'CASH', 'a', 1, 500)",
                // a completed sale without payment yet (to try problem B on), and a suspended one (problem A)
                SALE + "(81, 1, 5, 'INV-000081', 'COMPLETED', 10, 0, 0, 0, 10, 'a', 'a', 'a', 1, 0)",
                "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, total, "
                        + "created_at, updated_at) VALUES (82, 1, 5, 'INV-000082', 'SUSPENDED', 10, 0, 0, 0, 10, 'a', 'a')",
                "UPDATE cash_shifts SET status = 'CLOSED', closed_at = 'b', closed_by = 5, close_kind = 'CASHIER', "
                        + "expected_cash_minor = 50500, counted_cash_minor = 50500, difference_minor = 0, version = 1 WHERE shift_id = 1");
    }

    // ── Shift problems A–D (independent review): a closed shift's history cannot change ──

    @Test
    void shiftProblemsAtoD_closedShiftHistoryIsFrozen() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            closedShiftWithHistory(c);
            String before = one(c, EXPECTED.formatted(1));
            assertThat(before).isEqualTo("50500");
            // A: a suspended sale cannot hold a shift, and cannot be completed into the closed one
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET shift_id = 1 WHERE sale_id = 82"))
                    .hasMessageMatching("(?s).*(SALE_SHIFT_NEEDS_COMPLETED|SHIFT_CLOSED_RECORD).*");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET status = 'COMPLETED', completed_at = 'c', shift_id = 1 WHERE sale_id = 82"))
                    .hasMessageMatching("(?s).*(SALE_SHIFT_NOT_OPEN|SHIFT_CLOSED_RECORD).*");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET total = 99, subtotal = 99 WHERE sale_id = 80")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET status = 'CANCELED' WHERE sale_id = 80")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            assertThatThrownBy(() -> sql(c, "DELETE FROM sales WHERE sale_id = 80")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            // B: no new (cash or other) payment on a sale of the closed shift
            assertThatThrownBy(() -> sql(c, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) "
                    + "VALUES (81, 'CASH', 10, 'PAID', 'c')")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            // C: no deletion or change of its payment
            assertThatThrownBy(() -> sql(c, "DELETE FROM payments WHERE sale_id = 80")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            assertThatThrownBy(() -> sql(c, "UPDATE payments SET amount = 1 WHERE sale_id = 80")).hasMessageContaining("PAYMENT_NO_UPDATE");
            // D: no deletion or change of its cash return
            assertThatThrownBy(() -> sql(c, "DELETE FROM returns WHERE shift_id = 1")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            assertThatThrownBy(() -> sql(c, "UPDATE returns SET refund_minor = 1, refund_amount = 0.01 WHERE shift_id = 1"))
                    .hasMessageContaining("RETURN_FIXED");
            // its lines keep their amounts and cannot be removed; a later return may still raise returned_quantity
            assertThatThrownBy(() -> sql(c, "UPDATE sale_items SET line_total = 1 WHERE sale_item_id = 80")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            assertThatThrownBy(() -> sql(c, "DELETE FROM sale_items WHERE sale_item_id = 80")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            sql(c, "UPDATE sale_items SET returned_quantity = 2 WHERE sale_item_id = 80");
            // what Hibernate writes: EVERY column in the SET list, frozen ones with their SAME values —
            // allowed (SQLite's "UPDATE OF col" fires on the column list, so the triggers compare values)
            sql(c, "UPDATE sale_items SET sale_id = sale_id, quantity = quantity, unit_price = unit_price, "
                    + "line_total = line_total, unit_price_minor = unit_price_minor, returned_quantity = 1 WHERE sale_item_id = 80");
            sql(c, "UPDATE sales SET status = status, subtotal = subtotal, discount = discount, tax_rate = tax_rate, tax = tax, "
                    + "total = total, rounding_minor = rounding_minor, shift_id = shift_id, completed_at = completed_at, "
                    + "store_id = store_id, updated_at = 'z' WHERE sale_id = 80");
            // ... but one changed frozen value in such a full-row update is still refused
            assertThatThrownBy(() -> sql(c, "UPDATE sale_items SET sale_id = sale_id, quantity = 3, unit_price = unit_price, "
                    + "line_total = line_total WHERE sale_item_id = 80")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET status = status, total = total + 1, shift_id = shift_id "
                    + "WHERE sale_id = 80")).hasMessageContaining("SHIFT_CLOSED_RECORD");
            sql(c, "UPDATE sale_items SET returned_quantity = 2 WHERE sale_item_id = 80");
            // nothing changed the closed shift's balance; the record itself is frozen
            assertThat(one(c, EXPECTED.formatted(1))).isEqualTo(before);
            assertThat(one(c, "SELECT expected_cash_minor || '/' || counted_cash_minor || '/' || difference_minor FROM cash_shifts "
                    + "WHERE shift_id = 1")).isEqualTo("50500/50500/0");
            // a later refund of an old sale goes to the CURRENT open shift (the closed record stays as it was)
            sql(c, "INSERT INTO cash_shifts (store_id, drawer_id, cashier_id, status, opened_at, opening_cash_minor) VALUES (1, 1, 5, 'OPEN', 'd', 0)");
            assertThatThrownBy(() -> sql(c, "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, "
                    + "reason, refund_amount, refund_method, return_date, shift_id, refund_minor) VALUES (1, 80, 80, 2, 5, 1, 'x', 5, "
                    + "'CASH', 'e', 1, 500)")).hasMessageContaining("RETURN_SHIFT_NOT_OPEN");
            sql(c, "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                    + "refund_method, return_date, shift_id, refund_minor) VALUES (1, 80, 80, 2, 5, 1, 'x', 5, 'CASH', 'e', 2, 500)");
            assertThat(one(c, EXPECTED.formatted(2))).isEqualTo("-500");
            // even on an open shift, only a COMPLETED sale may carry it
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET shift_id = 2 WHERE sale_id = 82")).hasMessageContaining("SALE_SHIFT_NEEDS_COMPLETED");
            sql(c, "UPDATE sales SET status = 'COMPLETED', completed_at = 'f', shift_id = 2 WHERE sale_id = 82");
            assertThat(one(c, EXPECTED.formatted(1))).isEqualTo(before);
        }
    }

    @Test
    void races_doubleClose_refundVsClose_movementVsClose_doubleCompletion() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            openShift(c);
            sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, total, "
                    + "created_at, updated_at) VALUES (85, 1, 5, 'INV-000085', 'SUSPENDED', 10, 0, 0, 0, 10, 'a', 'a')");
        }
        String close = "UPDATE cash_shifts SET status = 'CLOSED', closed_at = 'b', closed_by = 5, close_kind = 'CASHIER', "
                + "expected_cash_minor = %d, counted_cash_minor = %d, difference_minor = %d, closing_note = %s, "
                + "version = version + 1 WHERE shift_id = 1 AND status = 'OPEN' AND version = %d";
        try (Connection a = open(); Connection b = open()) {
            sql(b, "PRAGMA busy_timeout = 100");
            // two attempts to complete the same invoice: the second sees it no longer SUSPENDED (0 rows)
            sql(a, "BEGIN IMMEDIATE");
            int first;
            try (Statement st = a.createStatement()) {
                first = st.executeUpdate("UPDATE sales SET status = 'COMPLETED', completed_at = 'c', shift_id = 1 "
                        + "WHERE sale_id = 85 AND status = 'SUSPENDED'");
            }
            sql(a, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (85, 'CASH', 10, 'PAID', 'c')");
            assertThatThrownBy(() -> sql(b, "BEGIN IMMEDIATE")).hasMessageContaining("BUSY");
            sql(a, "COMMIT");
            int second;
            try (Statement st = b.createStatement()) {
                second = st.executeUpdate("UPDATE sales SET status = 'COMPLETED', completed_at = 'c2', shift_id = 1 "
                        + "WHERE sale_id = 85 AND status = 'SUSPENDED'");
            }
            assertThat(first).isEqualTo(1);
            assertThat(second).isZero();
            assertThatThrownBy(() -> sql(b, "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) "
                    + "VALUES (85, 'CASH', 10, 'PAID', 'c2')")).hasMessageContaining("UNIQUE");

            // a cash movement while the cashier is closing: the close waits, then its stale amount is refused
            sql(a, "BEGIN IMMEDIATE", "INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, created_by, "
                    + "created_at, request_key) VALUES (1, 1, 'CASH_OUT', 2000, 'Bank deposit', 4, 'd', 'req-RACE0001')");
            assertThatThrownBy(() -> sql(b, close.formatted(51000, 51000, 0, "NULL", 0))).hasMessageContaining("BUSY");
            sql(a, "COMMIT");
            assertThatThrownBy(() -> sql(b, close.formatted(51000, 51000, 0, "NULL", 0))).hasMessageContaining("SHIFT_EXPECTED_MISMATCH");

            // a cash refund racing the close: the same — no refund can slip in unseen
            sql(a, "BEGIN IMMEDIATE", "INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, "
                    + "unit_price, line_total) VALUES (85, 85, 2, 'Milk', '22222222', 2, 5, 10)");
            // (the line exists before the refund; the refund itself)
            sql(a, "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                    + "refund_method, return_date, shift_id, refund_minor) VALUES (1, 85, 85, 2, 5, 1, 'x', 5, 'CASH', 'e', 1, 500)");
            assertThatThrownBy(() -> sql(b, close.formatted(49000, 49000, 0, "NULL", 0))).hasMessageContaining("BUSY");
            sql(a, "COMMIT");
            assertThatThrownBy(() -> sql(b, close.formatted(49000, 49000, 0, "NULL", 0))).hasMessageContaining("SHIFT_EXPECTED_MISMATCH");
            // 500 + 10 − 20 − 5 = 485.00
            assertThat(one(b, EXPECTED.formatted(1))).isEqualTo("48500");

            // two closes of the same shift: one wins (optimistic version), the other changes nothing
            int c1, c2;
            try (Statement st = a.createStatement()) {
                c1 = st.executeUpdate(close.formatted(48500, 48000, -500, "'5.00 short'", 0));
            }
            try (Statement st = b.createStatement()) {
                c2 = st.executeUpdate(close.formatted(48500, 48500, 0, "NULL", 0));
            }
            assertThat(c1).isEqualTo(1);
            assertThat(c2).isZero();
            assertThat(one(b, "SELECT counted_cash_minor || '/' || difference_minor || '/' || version FROM cash_shifts WHERE shift_id = 1"))
                    .isEqualTo("48000/-500/1");
        }
    }

    @Test
    void returns_amountRules_methodMatchesSale_noOverRefund_roundingCashOnly() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            // sale 2: 2 × Milk 11.60 paid by CARD (legacy)
            String ret = "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                    + "refund_amount, refund_method, return_date, refund_minor, rounding_minor) VALUES (1, 2, 3, 2, 5, 1, 'x', %s, '%s', 'a', %s, %d)";
            assertThatThrownBy(() -> sql(c, ret.formatted("5.8", "CASH", "580", 0))).hasMessageContaining("RETURN_AMOUNT_INVALID"); // wrong method
            assertThatThrownBy(() -> sql(c, ret.formatted("5.8", "CARD", "590", 0))).hasMessageContaining("RETURN_AMOUNT_INVALID"); // minor ≠ REAL
            assertThatThrownBy(() -> sql(c, ret.formatted("5.8", "CARD", "580", 2))).hasMessageContaining("RETURN_AMOUNT_INVALID"); // rounding on card
            sql(c, ret.formatted("5.8", "CARD", "580", 0), ret.formatted("5.8", "CARD", "580", 0));
            assertThatThrownBy(() -> sql(c, ret.formatted("0.01", "CARD", "1", 0))).hasMessageContaining("RETURN_AMOUNT_INVALID"); // over 11.60
            // legacy returns (no minor amount) still count: sale 1 paid 12.76, 5.32 already refunded
            assertThatThrownBy(() -> sql(c, "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, "
                    + "reason, refund_amount, refund_method, return_date, refund_minor) VALUES (1, 1, 1, 2, 5, 1, 'x', 7.45, 'CASH', 'a', 745)"))
                    .hasMessageContaining("RETURN_AMOUNT_INVALID");
            sql(c, "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                    + "refund_method, return_date, refund_minor) VALUES (1, 1, 1, 2, 5, 1, 'x', 7.44, 'CASH', 'a', 744)");
            assertThatThrownBy(() -> sql(c, "UPDATE returns SET refund_minor = 1 WHERE return_id = 1")).hasMessageContaining("RETURN_FIXED");
        }
    }

    @Test
    void expectedCash_formulaOnSchema_matchesTheRequirementExample() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            sql(c, "INSERT INTO cash_drawers (store_id, name, created_at) VALUES (1, 'Main', 'a')",
                    "INSERT INTO cash_shifts (store_id, drawer_id, cashier_id, status, opened_at, opening_cash_minor) "
                            + "VALUES (1, 1, 5, 'OPEN', 'a', 50000)",
                    // cash sales 1,200; a card sale and a cheque sale do NOT count; one cash refund of 100
                    SALE + "(70, 1, 5, 'INV-000070', 'COMPLETED', 1000, 0, 0, 0, 1000, 'a', 'a', 'a', 1, 0)",
                    SALE + "(71, 1, 5, 'INV-000071', 'COMPLETED', 200, 0, 0, 0, 200, 'a', 'a', 'a', 1, 0)",
                    SALE + "(72, 1, 5, 'INV-000072', 'COMPLETED', 300, 0, 0, 0, 300, 'a', 'a', 'a', 1, 0)",
                    SALE + "(73, 1, 5, 'INV-000073', 'COMPLETED', 400, 0, 0, 0, 400, 'a', 'a', 'a', 1, 0)",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at, tendered_minor, change_minor) "
                            + "VALUES (70, 'CASH', 1000, 'PAID', 'a', 100000, 0), (71, 'CASH', 200, 'PAID', 'a', 50000, 30000)",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES "
                            + "(72, 'CARD', 300, 'PAID', 'a'), (73, 'CHEQUE', 400, 'PAID', 'a')",
                    "INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price, line_total) "
                            + "VALUES (70, 70, 2, 'Milk', '22222222', 200, 5, 1000)",
                    "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                            + "refund_method, return_date, shift_id, refund_minor) VALUES (1, 70, 70, 2, 5, 20, 'x', 100, 'CASH', 'a', 1, 10000)");
            String expected = """
                    SELECT s.opening_cash_minor
                      + coalesce((SELECT sum(CAST(round(p.amount * 100) AS INTEGER)) FROM payments p JOIN sales x ON x.sale_id = p.sale_id
                                  WHERE x.shift_id = s.shift_id AND p.payment_method = 'CASH'), 0)
                      - coalesce((SELECT sum(coalesce(r.refund_minor, CAST(round(r.refund_amount * 100) AS INTEGER)))
                                  FROM returns r WHERE r.shift_id = s.shift_id AND r.refund_method = 'CASH'), 0)
                      + coalesce((SELECT sum(amount_minor) FROM cash_movements m WHERE m.shift_id = s.shift_id AND m.type = 'CASH_IN'), 0)
                      - coalesce((SELECT sum(amount_minor) FROM cash_movements m WHERE m.shift_id = s.shift_id
                                  AND m.type IN ('CASH_OUT', 'CHEQUE_REFUND_OUT')), 0)
                    FROM cash_shifts s WHERE s.shift_id = 1""";
            // 500 + 1,200 − 100 = 1,600.00 — the 300 change given back is not in the drawer, card / cheque excluded
            assertThat(one(c, expected)).isEqualTo("160000");
        }
    }

    // ── 5. Packages (piece / carton) and history ───────────────────────────────────

    @Test
    void packages_barcodesUnambiguous_sizeFixed_lineSnapshotKeptWhenConfigChanges() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            String pkg = "INSERT INTO product_packages (store_id, product_id, name, units_per_package, price_minor, barcode, "
                    + "created_at, updated_at) VALUES (%d, %d, '%s', %d, %d, %s, 'a', 'a')";
            assertThatThrownBy(() -> sql(c, pkg.formatted(1, 1, "Carton", 24, 4000, "'22222222'")))
                    .hasMessageContaining("BARCODE_USED_BY_PRODUCT");
            assertThatThrownBy(() -> sql(c, pkg.formatted(1, 1, "Carton", 1, 4000, "NULL"))).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql(c, pkg.formatted(1, 1, "Carton", 24, 0, "NULL"))).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql(c, pkg.formatted(1, 1, "Carton", 24, 4000, "'12AB5678'"))).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql(c, pkg.formatted(1, 3, "Carton", 24, 4000, "NULL"))).hasMessageContaining("FOREIGN KEY");
            sql(c, pkg.formatted(1, 1, "Carton 24", 24, 4000, "'40000024'"), pkg.formatted(1, 1, "Pack 6", 6, 1100, "NULL"));
            // optional barcode: two packages without one are fine; the same barcode twice is not
            assertThatThrownBy(() -> sql(c, pkg.formatted(1, 2, "Box", 12, 5500, "'40000024'"))).hasMessageContaining("UNIQUE");
            sql(c, pkg.formatted(2, 3, "Carton 24", 24, 15000, "'40000024'")); // another store may use it
            // a product cannot take a package barcode of its store
            assertThatThrownBy(() -> sql(c, "UPDATE products SET barcode = '40000024' WHERE product_id = 2"))
                    .hasMessageContaining("BARCODE_USED_BY_PACKAGE");
            assertThatThrownBy(() -> sql(c, "UPDATE product_packages SET units_per_package = 12 WHERE package_id = 1"))
                    .hasMessageContaining("PACKAGE_IDENTITY_FIXED");

            // a sale line of 2 cartons: snapshot of name, size and price
            sql(c, SALE + "(80, 1, 5, 'INV-000080', 'COMPLETED', 80, 0, 0, 0, 80, 'a', 'a', 'a', NULL, 0)",
                    "INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price, line_total, "
                            + "package_id, package_name, units_per_package, unit_price_minor) "
                            + "VALUES (80, 80, 1, 'Water 1.5L', '40000024', 2, 40, 80, 1, 'Carton 24', 24, 4000)");
            assertThatThrownBy(() -> sql(c, "INSERT INTO sale_items (sale_id, product_id, product_name, quantity, unit_price, "
                    + "line_total, package_id, package_name, units_per_package) VALUES (80, 1, 'Water', 1, 40, 40, 1, 'Carton 24', 12)"))
                    .hasMessageContaining("SALE_ITEM_PACKAGE_SNAPSHOT"); // wrong size
            assertThatThrownBy(() -> sql(c, "INSERT INTO sale_items (sale_id, product_id, product_name, quantity, unit_price, "
                    + "line_total, units_per_package) VALUES (80, 1, 'Water', 1, 2, 2, 24)"))
                    .hasMessageContaining("SALE_ITEM_PACKAGE_SNAPSHOT"); // size without a package
            // price change and deactivation later: the sold line keeps its own price and size
            sql(c, "UPDATE product_packages SET price_minor = 4500, active = 0 WHERE package_id = 1");
            assertThat(one(c, "SELECT unit_price_minor || '/' || units_per_package || '/' || package_name FROM sale_items "
                    + "WHERE sale_item_id = 80")).isEqualTo("4000/24/Carton 24");
            assertThatThrownBy(() -> sql(c, "UPDATE sale_items SET units_per_package = 12 WHERE sale_item_id = 80"))
                    .hasMessageContaining("SALE_ITEM_SNAPSHOT_FIXED");
            // base-unit stock movements read from the snapshot: 2 cartons = 48 pieces; return 1 carton = +24
            assertThat(one(c, "SELECT quantity * units_per_package FROM sale_items WHERE sale_item_id = 80")).isEqualTo("48");
            // the product deleted later: the line keeps name, barcode, size and price (history intact)
            sql(c, "DELETE FROM product_favorites", "DELETE FROM product_packages WHERE product_id = 1",
                    "UPDATE sale_items SET product_id = NULL WHERE product_id = 1", "DELETE FROM products WHERE product_id = 1");
            assertThat(one(c, "SELECT coalesce(package_id, 'null') || '/' || package_name || '/' || units_per_package || '/' "
                    + "|| unit_price_minor FROM sale_items WHERE sale_item_id = 80")).isEqualTo("null/Carton 24/24/4000");
        }
    }

    /** Reorder = one transaction: remove the store's favorites, insert them again at 0..n-1. */
    private static void reorder(Connection c, int store, int admin, long... productIds) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            sql(c, "DELETE FROM product_favorites WHERE store_id = " + store);
            for (int i = 0; i < productIds.length; i++) {
                sql(c, "INSERT INTO product_favorites (store_id, product_id, position, created_by, created_at) VALUES ("
                        + store + ", " + productIds[i] + ", " + i + ", " + admin + ", 'a')");
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static String order(Connection c, int store) throws SQLException {
        return one(c, "SELECT group_concat(product_id, ',') FROM (SELECT product_id FROM product_favorites WHERE store_id = "
                + store + " ORDER BY position)");
    }

    // ── Reported defect 4: favorite positions are unique, ordering is deterministic ──

    @Test
    void defect4_favorites_uniquePositions_safeReorder_storeAdminOnly_isolated() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            sql(c, "INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price, quantity, "
                    + "min_stock, created_at, updated_at) VALUES (4, 1, 1, 'Sugar', '44444444', 'groceries', 4, 9, 1, 'a', 'a')");
            String fav = "INSERT INTO product_favorites (store_id, product_id, position, created_by, created_at) VALUES (%d, %d, %d, %d, 'a')";
            sql(c, fav.formatted(1, 1, 0, 4));
            // BEFORE the fix this succeeded: two favorites on the same position
            assertThatThrownBy(() -> sql(c, fav.formatted(1, 2, 0, 4))).hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> sql(c, fav.formatted(1, 1, 1, 4))).hasMessageContaining("UNIQUE"); // same product twice
            assertThatThrownBy(() -> sql(c, fav.formatted(1, 2, 1, 5))).hasMessageContaining("FAVORITE_ACTOR_NOT_STORE_ADMIN");
            assertThatThrownBy(() -> sql(c, fav.formatted(1, 3, 1, 4))).hasMessageContaining("FOREIGN KEY"); // store 2 product
            assertThatThrownBy(() -> sql(c, fav.formatted(2, 3, 0, 4))).hasMessageContaining("FOREIGN KEY"); // store 1 admin
            assertThatThrownBy(() -> sql(c, fav.formatted(1, 2, -1, 4))).hasMessageContaining("CHECK");
            sql(c, fav.formatted(1, 2, 1, 4), fav.formatted(1, 4, 2, 4));
            assertThat(order(c, 1)).isEqualTo("1,2,4");
            // a naive swap through direct updates hits the unique position and changes nothing
            c.setAutoCommit(false);
            assertThatThrownBy(() -> sql(c, "UPDATE product_favorites SET position = 0 WHERE product_id = 4")).hasMessageContaining("UNIQUE");
            c.rollback();
            c.setAutoCommit(true);
            assertThat(order(c, 1)).isEqualTo("1,2,4");
            // reorder (Sugar, Water, Milk), repeated: same result, never a duplicate position
            reorder(c, 1, 4, 4, 1, 2);
            reorder(c, 1, 4, 4, 1, 2);
            assertThat(order(c, 1)).isEqualTo("4,1,2");
            assertThat(one(c, "SELECT group_concat(position, ',') FROM (SELECT position FROM product_favorites WHERE store_id = 1 ORDER BY position)"))
                    .isEqualTo("0,1,2");
            // a reorder that fails half way (a duplicate in the request) leaves the old order intact
            assertThatThrownBy(() -> reorder(c, 1, 4, 2, 2, 1)).hasMessageContaining("UNIQUE");
            assertThat(order(c, 1)).isEqualTo("4,1,2");
            // remove: a gap is fine — ordering stays deterministic
            sql(c, "DELETE FROM product_favorites WHERE store_id = 1 AND product_id = 1");
            assertThat(order(c, 1)).isEqualTo("4,2");
            // store 2 is untouched by store 1's reorders
            sql(c, fav.formatted(2, 3, 0, 8));
            reorder(c, 1, 4, 2, 4);
            assertThat(order(c, 2)).isEqualTo("3");
            // a product deleted later disappears from the favorites
            sql(c, "UPDATE sale_items SET product_id = NULL WHERE product_id = 2", "DELETE FROM products WHERE product_id = 2");
            assertThat(order(c, 1)).isEqualTo("4");
        }
        // two admins reorder at the same time: SQLite serialises them; the final order is one of the two, valid
        try (Connection a = open(); Connection b = open()) {
            sql(b, "PRAGMA busy_timeout = 5000");
            sql(a, "INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price, quantity, "
                    + "min_stock, created_at, updated_at) VALUES (5, 1, 1, 'Rice', '55555555', 'groceries', 4, 9, 1, 'a', 'a')");
            a.setAutoCommit(false);
            sql(a, "DELETE FROM product_favorites WHERE store_id = 1",
                    "INSERT INTO product_favorites (store_id, product_id, position, created_by, created_at) VALUES (1, 5, 0, 4, 'a'), (1, 4, 1, 4, 'a')");
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(200);
                    a.commit();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.start();
            reorder(b, 1, 4, 4, 5); // waits for A, then applies its own complete order
            t.join();
            assertThat(order(b, 1)).isEqualTo("4,5");
            assertThat(n(b, "SELECT COUNT(*) FROM (SELECT position FROM product_favorites WHERE store_id = 1 GROUP BY position HAVING COUNT(*) > 1)")).isZero();
        }
    }

    // ── 6. Rounding and refunds (pure rules) ───────────────────────────────────────

    @Test
    void rounding_cashOnly_nearestStep_midpointUp() {
        assertThat(adjustment(1997, 10, true)).isEqualTo(3);    // 19.97 → 20.00
        assertThat(adjustment(1994, 10, true)).isEqualTo(-4);   // 19.94 → 19.90
        assertThat(adjustment(1995, 10, true)).isEqualTo(5);    // midpoint → up
        assertThat(adjustment(1997, 5, true)).isEqualTo(-2);    // → 19.95
        assertThat(adjustment(1998, 5, true)).isEqualTo(2);     // → 20.00
        assertThat(adjustment(1925, 50, true)).isEqualTo(25);   // midpoint → 19.50
        assertThat(adjustment(1950, 100, true)).isEqualTo(50);  // midpoint → 20.00
        assertThat(adjustment(1997, 0, true)).isZero();         // default: no rounding
        assertThat(adjustment(1997, 10, false)).isZero();       // card / wallet / transfer / cheque
        for (int step : new int[]{5, 10, 50, 100}) {
            for (long t = 1; t < 5000; t++) {
                long a = adjustment(t, step, true);
                assertThat(Math.abs(a)).isLessThanOrEqualTo(step / 2);
                assertThat((t + a) % step).isZero();
            }
        }
        // change on the rounded amount: total 19.97, pays 20.00, tendered 50 → change 30.00
        long payable = 1997 + adjustment(1997, 10, true);
        assertThat(5000 - payable).isEqualTo(3000);
    }

    @Test
    void refunds_partialThenLast_sumEqualsWhatWasPaid_roundingAppliedOnce() {
        // total 19.97 for 3 equal units, paid 20.00 cash (step 10)
        long paid = 2000;
        long r1 = cashRefund(666, 10, paid, 0, false);          // exact share 6.66 → 6.70
        long r2 = cashRefund(666, 10, paid, r1, false);         // → 6.70
        long r3 = cashRefund(665, 10, paid, r1 + r2, true);     // last: what is left
        assertThat(r1).isEqualTo(670);
        assertThat(r2).isEqualTo(670);
        assertThat(r3).isEqualTo(660);
        assertThat(r1 + r2 + r3).isEqualTo(paid);
        // a single full return refunds exactly what was paid (rounding included, once)
        assertThat(cashRefund(1997, 10, paid, 0, true)).isEqualTo(2000);
        // never more than what is left
        assertThat(cashRefund(1999, 10, 2000, 1500, false)).isEqualTo(500);
        assertThatThrownBy(() -> cashRefund(10, 10, 2000, 2100, false)).hasMessageContaining("over-refunded");
        // no rounding store: exact shares, last one settles the cent
        long a = cashRefund(333, 0, 1000, 0, false), b = cashRefund(333, 0, 1000, a, false), d = cashRefund(334, 0, 1000, a + b, true);
        assertThat(a + b + d).isEqualTo(1000);
    }
}
