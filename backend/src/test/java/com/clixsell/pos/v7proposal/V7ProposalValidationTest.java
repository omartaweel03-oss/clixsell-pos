package com.clixsell.pos.v7proposal;

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

import static com.clixsell.pos.v7proposal.CashRoundingProposal.adjustment;
import static com.clixsell.pos.v7proposal.CashRoundingProposal.cashRefund;
import static com.clixsell.pos.v7proposal.CashRoundingProposal.roundToStep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validation of the PROPOSED V7 (not applied to the application): disposable V6 databases with
 * representative history, the backup procedure, the payments rebuild, every new constraint and
 * trigger, failure handling, and the cash rounding / refund rules.
 */
public class V7ProposalValidationTest {

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
        return V7StoreOperationsProposal.count(c, q);
    }

    private static String one(Connection c, String q) throws SQLException {
        return V7StoreOperationsProposal.one(c, q);
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
        return V7StoreOperationsProposal.migrate(db, null, null).backup();
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
            for (String t : V7StoreOperationsProposal.tables(c)) cols.put(t, V7StoreOperationsProposal.columns(c, t));
            before = V7StoreOperationsProposal.hashes(c, cols);
            rowsBefore = V7StoreOperationsProposal.counts(c, cols.keySet());
            objectsBefore = V7StoreOperationsProposal.objects(c);
        }
        String dbHashBefore = fileHash(db);

        Path backup = migrate();

        // backup: next to the database, safe unique name, identical to the database before V7
        assertThat(backup.getParent()).isEqualTo(db.toAbsolutePath().getParent());
        assertThat(backup.getFileName().toString()).matches("clixsell-backup-before-v7-\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}\\.db");
        try (Connection b = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            assertThat(one(b, "PRAGMA integrity_check")).isEqualTo("ok");
            assertThat(V7StoreOperationsProposal.hashes(b, cols)).isEqualTo(before);
            assertThat(n(b, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'cash_shifts'")).isZero();
        }
        assertThat(fileHash(db)).isNotEqualTo(dbHashBefore); // the database itself was migrated

        try (Connection c = open()) {
            assertThat(V7StoreOperationsProposal.hashes(c, cols)).as("old values").isEqualTo(before);
            assertThat(V7StoreOperationsProposal.counts(c, cols.keySet())).isEqualTo(rowsBefore);
            assertThat(V7StoreOperationsProposal.objects(c)).containsAll(objectsBefore);
            for (String t : V7StoreOperationsProposal.NEW_TABLES) assertThat(n(c, "SELECT COUNT(*) FROM " + t)).as(t).isZero();
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
        assertThatThrownBy(() -> V7StoreOperationsProposal.migrate(db, null, "taken.db"))
                .isInstanceOf(V7StoreOperationsProposal.BackupFailed.class)
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
            for (String t : V7StoreOperationsProposal.tables(c)) cols.put(t, V7StoreOperationsProposal.columns(c, t));
            before = V7StoreOperationsProposal.hashes(c, cols);
            schemaBefore = V7StoreOperationsProposal.objects(c);
        }
        int drop = V7StoreOperationsProposal.SCHEMA.indexOf("DROP TABLE payments");
        assertThat(drop).isPositive();
        assertThatThrownBy(() -> V7StoreOperationsProposal.migrate(db, i -> {
            if (i == drop) throw new IllegalStateException("simulated crash after DROP TABLE payments");
        }, null)).hasMessageContaining("simulated crash");
        try (Connection c = open()) {
            assertThat(V7StoreOperationsProposal.objects(c)).isEqualTo(schemaBefore); // schema exactly V6
            assertThat(V7StoreOperationsProposal.hashes(c, cols)).isEqualTo(before);   // every value
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
            // rounding on a card sale, wrong amount, change that does not add up, unknown method
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
                    .hasMessageContaining("CHECK"); // due date must be a real ISO date
            sql(c, cheque(pay, 32, "100", "Bank of Palestine", 1160, "RECEIVED"));
            sql(c, SALE + "(33, 1, 5, 'INV-000033', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', NULL, 0)",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (33, 'CHEQUE', 11.6, 'PAID', 'a')");
            long pay2 = n(c, "SELECT payment_id FROM payments WHERE sale_id = 33");
            assertThatThrownBy(() -> sql(c, cheque(pay2, 33, "100", "BANK OF PALESTINE", 1160, "RECEIVED")))
                    .hasMessageContaining("UNIQUE"); // same cheque twice

            // status: RECEIVED → DEPOSITED → CLEARED; no skipping, no going back, never deleted
            long chq = n(c, "SELECT cheque_id FROM cheques WHERE sale_id = 32");
            assertThatThrownBy(() -> sql(c, status(chq, "CLEARED", 1))).hasMessageContaining("CHEQUE_BAD_TRANSITION");
            sql(c, status(chq, "DEPOSITED", 1), status(chq, "CLEARED", 2));
            assertThatThrownBy(() -> sql(c, status(chq, "RECEIVED", 3))).hasMessageContaining("CHEQUE_BAD_TRANSITION");
            assertThatThrownBy(() -> sql(c, "DELETE FROM cheques WHERE cheque_id = " + chq)).hasMessageContaining("CHEQUE_NO_DELETE");
            // history: status changes by a Store Admin only; append only
            assertThatThrownBy(() -> sql(c, "INSERT INTO cheque_events (cheque_id, store_id, from_status, to_status, user_id, created_at) "
                    + "VALUES (" + chq + ", 1, 'RECEIVED', 'DEPOSITED', 5, 'a')")).hasMessageContaining("CHEQUE_ACTOR_NOT_STORE_ADMIN");
            sql(c, "INSERT INTO cheque_events (cheque_id, store_id, from_status, to_status, user_id, created_at) "
                    + "VALUES (" + chq + ", 1, NULL, 'RECEIVED', 5, 'a'), (" + chq + ", 1, 'RECEIVED', 'DEPOSITED', 4, 'b')");
            assertThatThrownBy(() -> sql(c, "UPDATE cheque_events SET note = 'x'")).hasMessageContaining("APPEND_ONLY");
            // another store cannot point at this store's sale (composite key)
            assertThatThrownBy(() -> sql(c, "INSERT INTO cheques (store_id, sale_id, payment_id, cheque_number, bank_name, "
                    + "amount_minor, due_date, status, created_by, created_at, updated_at) VALUES (2, 33, " + pay2
                    + ", '7', 'Bank', 1160, '2026-12-31', 'RECEIVED', 6, 'a', 'a')")).hasMessageContaining("FOREIGN KEY");
        }
    }

    private static String cheque(long paymentId, long saleId, String number, String bank, long amountMinor, String status) {
        return "INSERT INTO cheques (store_id, sale_id, payment_id, cheque_number, bank_name, amount_minor, due_date, status, "
                + "created_by, created_at, updated_at) VALUES (1, " + saleId + ", " + paymentId + ", '" + number + "', '" + bank
                + "', " + amountMinor + ", '2026-12-31', '" + status + "', 5, '2026-11-28T09:00:00', '2026-11-28T09:00:00')";
    }

    private static String status(long chequeId, String s, int version) {
        return "UPDATE cheques SET status = '" + s + "', version = " + version + ", updated_at = 'x' WHERE cheque_id = " + chequeId;
    }

    @Test
    void chequeReturns_storeAdminOnly_refundPaidOnlyAfterClearing_neverCashAtReturnTime() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            sql(c, SALE + "(40, 1, 5, 'INV-000040', 'COMPLETED', 10, 0, 0, 0, 10, 'a', 'a', 'a', NULL, 0)",
                    "INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price, line_total) "
                            + "VALUES (40, 40, 2, 'Milk', '22222222', 2, 5, 10)",
                    "INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (40, 'CHEQUE', 10, 'PAID', 'a')");
            long pay = n(c, "SELECT payment_id FROM payments WHERE sale_id = 40");
            sql(c, cheque(pay, 40, "555", "Arab Bank", 1000, "RECEIVED"));
            long chq = n(c, "SELECT cheque_id FROM cheques WHERE sale_id = 40");
            String ret = "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, "
                    + "refund_amount, refund_method, return_date, refund_minor) VALUES (1, 40, 40, 2, %d, 1, 'Damaged', 5, '%s', 'a', 500)";
            // a cashier cannot do it; nobody can pay cash out at return time for a cheque sale
            assertThatThrownBy(() -> sql(c, ret.formatted(5, "CHEQUE"))).hasMessageContaining("CHEQUE_RETURN_NEEDS_STORE_ADMIN");
            assertThatThrownBy(() -> sql(c, ret.formatted(4, "CASH"))).hasMessageContaining("CHEQUE_RETURN_NEEDS_STORE_ADMIN");
            sql(c, ret.formatted(4, "CHEQUE"));
            long retId = n(c, "SELECT max(return_id) FROM returns");
            sql(c, "INSERT INTO cheque_refunds (store_id, return_id, cheque_id, amount_minor, status, created_at) "
                    + "VALUES (1, " + retId + ", " + chq + ", 500, 'PENDING', 'a')");
            long cr = n(c, "SELECT cheque_refund_id FROM cheque_refunds");
            String pay500 = "UPDATE cheque_refunds SET status = 'PAID', paid_method = 'CASH', decided_by = %d, decided_at = 'b', "
                    + "version = version + 1 WHERE cheque_refund_id = " + cr;
            // not cleared yet → cannot be paid; a cashier cannot decide
            assertThatThrownBy(() -> sql(c, pay500.formatted(4))).hasMessageContaining("CHEQUE_REFUND_BAD_DECISION");
            sql(c, status(chq, "DEPOSITED", 1), status(chq, "CLEARED", 2));
            assertThatThrownBy(() -> sql(c, pay500.formatted(5))).hasMessageContaining("CHEQUE_REFUND_BAD_DECISION");
            sql(c, pay500.formatted(4));
            assertThatThrownBy(() -> sql(c, "UPDATE cheque_refunds SET status = 'CANCELLED', paid_method = NULL, reason = 'again', "
                    + "version = version + 1 WHERE cheque_refund_id = " + cr)).hasMessageContaining("CHEQUE_REFUND_BAD_DECISION");
            // the cash leaves the drawer only as a recorded movement in an OPEN shift, by a Store Admin
            sql(c, "INSERT INTO cash_drawers (store_id, name, created_at) VALUES (1, 'Main', 'a')",
                    "INSERT INTO cash_shifts (store_id, drawer_id, cashier_id, status, opened_at, opening_cash_minor) "
                            + "VALUES (1, 1, 5, 'OPEN', 'a', 50000)");
            sql(c, "INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, cheque_refund_id, created_by, "
                    + "created_at, request_key) VALUES (1, 1, 'CHEQUE_REFUND_OUT', 500, 'Refund INV-000040', " + cr + ", 4, 'c', 'req-00000001')");
            assertThatThrownBy(() -> sql(c, "INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, "
                    + "cheque_refund_id, created_by, created_at, request_key) VALUES (1, 1, 'CHEQUE_REFUND_OUT', 500, 'Twice', "
                    + cr + ", 4, 'c', 'req-00000002')")).hasMessageContaining("UNIQUE"); // no double refund
        }
    }

    // ── 4. Shifts and cash movements ────────────────────────────────────────────────

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

            // sales on shift 1; another store's shift refused
            sql(c, SALE + "(50, 1, 5, 'INV-000050', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', 1, 0)");
            assertThatThrownBy(() -> sql(c, SALE + "(51, 1, 5, 'INV-000051', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', 3, 0)"))
                    .hasMessageContaining("SALE_SHIFT_NOT_OPEN");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET shift_id = 2 WHERE sale_id = 50"))
                    .hasMessageContaining("SALE_SHIFT_NOT_OPEN"); // never moved to another shift

            // movements: Store Admin only (D3), reason required, no duplicates, append only
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

            // closing: difference = counted − expected, note required when not zero, by the cashier
            String close = "UPDATE cash_shifts SET status = 'CLOSED', closed_at = 'b', closed_by = %d, close_kind = '%s', "
                    + "close_reason = %s, expected_cash_minor = 160000, counted_cash_minor = 158000, difference_minor = %d, "
                    + "closing_note = %s, version = version + 1 WHERE shift_id = 1";
            assertThatThrownBy(() -> sql(c, close.formatted(5, "CASHIER", "NULL", -2000, "NULL"))).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql(c, close.formatted(5, "CASHIER", "NULL", 0, "'ok'"))).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql(c, close.formatted(7, "CASHIER", "NULL", -2000, "'Short by 20'")))
                    .hasMessageContaining("SHIFT_CLOSER_NOT_ALLOWED"); // not their shift
            sql(c, close.formatted(5, "CASHIER", "NULL", -2000, "'Short by 20'"));
            // closed: frozen — no second closing, no new sale or movement, never deleted
            assertThatThrownBy(() -> sql(c, close.formatted(5, "CASHIER", "NULL", -2000, "'again'"))).hasMessageContaining("SHIFT_CLOSED");
            assertThatThrownBy(() -> sql(c, SALE + "(52, 1, 5, 'INV-000052', 'COMPLETED', 10, 0, 16, 1.6, 11.6, 'a', 'a', 'a', 1, 0)"))
                    .hasMessageContaining("SALE_SHIFT_NOT_OPEN");
            assertThatThrownBy(() -> sql(c, mv.formatted("CASH_IN", 100, "Late", 4, "k-0000003")))
                    .hasMessageContaining("CASH_MOVEMENT_NOT_ALLOWED");
            assertThatThrownBy(() -> sql(c, "DELETE FROM cash_shifts WHERE shift_id = 1")).hasMessageContaining("SHIFT_NO_DELETE");

            // exceptional closure by a Store Admin needs a reason; a cashier cannot use it
            String admin = "UPDATE cash_shifts SET status = 'CLOSED', closed_at = 'b', closed_by = %d, close_kind = 'ADMIN_EXCEPTION', "
                    + "close_reason = %s, expected_cash_minor = 50000, counted_cash_minor = 50000, difference_minor = 0, "
                    + "version = version + 1 WHERE shift_id = 2";
            assertThatThrownBy(() -> sql(c, admin.formatted(4, "NULL"))).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> sql(c, admin.formatted(5, "'Cashier left early'"))).hasMessageContaining("SHIFT_CLOSER_NOT_ALLOWED");
            sql(c, admin.formatted(4, "'Cashier left early'"));
            // the drawer is free again: next cashier opens (shared drawer = hand-over)
            sql(c, open.formatted(1, 1, 7));

            // mandatory shifts (D2): a completed sale and a cash refund need an open shift
            sql(c, "UPDATE stores SET require_cash_shift = 1 WHERE store_id = 2");
            assertThatThrownBy(() -> sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, "
                    + "discount, tax_rate, tax, total, created_at, updated_at, completed_at) VALUES (60, 2, 6, 'INV-000060', "
                    + "'COMPLETED', 8, 0, 16, 1.28, 9.28, 'a', 'a', 'a')")).hasMessageContaining("SHIFT_REQUIRED");
            sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, "
                    + "total, created_at, updated_at) VALUES (61, 2, 6, 'INV-000061', 'SUSPENDED', 8, 0, 16, 1.28, 9.28, 'a', 'a')");
            assertThatThrownBy(() -> sql(c, "UPDATE sales SET status = 'COMPLETED' WHERE sale_id = 61"))
                    .hasMessageContaining("SHIFT_REQUIRED"); // a suspended invoice belongs to no shift until completed
            sql(c, "UPDATE sales SET status = 'COMPLETED', shift_id = 3 WHERE sale_id = 61");
            assertThatThrownBy(() -> sql(c, "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, "
                    + "reason, refund_amount, refund_method, return_date) VALUES (2, 7, 8, 3, 6, 1, 'x', 9.28, 'CASH', 'a')"))
                    .hasMessageContaining("SHIFT_REQUIRED");
            sql(c, "INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                    + "refund_method, return_date, shift_id) VALUES (2, 7, 8, 3, 6, 1, 'x', 9.28, 'CASH', 'a', 3)");
            // a legacy store (require_cash_shift = 0) keeps working exactly as before
            sql(c, "INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, "
                    + "total, created_at, updated_at, completed_at) VALUES (62, 1, 5, 'INV-000062', 'COMPLETED', 8, 0, 16, 1.28, "
                    + "9.28, 'a', 'a', 'a')");
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

    @Test
    void favorites_storeAdminOnly_noDuplicates_sameStoreOnly_removedWithTheProduct() throws Exception {
        fillV6();
        migrate();
        try (Connection c = open()) {
            String fav = "INSERT INTO product_favorites (store_id, product_id, position, created_by, created_at) VALUES (%d, %d, %d, %d, 'a')";
            assertThatThrownBy(() -> sql(c, fav.formatted(1, 1, 0, 5))).hasMessageContaining("FAVORITE_ACTOR_NOT_STORE_ADMIN");
            sql(c, fav.formatted(1, 1, 0, 4), fav.formatted(1, 2, 1, 4));
            assertThatThrownBy(() -> sql(c, fav.formatted(1, 1, 2, 4))).hasMessageContaining("UNIQUE");
            assertThatThrownBy(() -> sql(c, fav.formatted(1, 3, 2, 4))).hasMessageContaining("FOREIGN KEY"); // store 2 product
            assertThatThrownBy(() -> sql(c, fav.formatted(2, 3, 0, 4))).hasMessageContaining("FOREIGN KEY"); // store 1 admin
            sql(c, "UPDATE sale_items SET product_id = NULL WHERE product_id = 2", "DELETE FROM products WHERE product_id = 2");
            assertThat(n(c, "SELECT COUNT(*) FROM product_favorites")).isEqualTo(1);
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
