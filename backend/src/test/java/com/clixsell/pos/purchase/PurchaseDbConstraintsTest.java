package com.clixsell.pos.purchase;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * V6 purchase-order constraints and triggers, tested with DIRECT SQL on a
 * temporary database (it deliberately bypasses the application): what must be
 * refused is refused, and what is legitimate (including the existing "delete
 * product" feature) still works.
 *
 * <p>Fixture: store 1 "Shini" (Guardian ON) and store 2 "Neshan" (Guardian OFF);
 * users 10 adminS, 11 cashierS, 12 admin2S (Store Admins / Cashier of store 1),
 * 20 adminN (store 2), 1 sysadmin; suppliers 1 SA, 2 SB, 4 SC (inactive) of store 1,
 * 3 NA of store 2; products P1, P2 (SA), P3 (SB), P5 (SC) of store 1 and Q1 (NA) of store 2.
 */
class PurchaseDbConstraintsTest {

    private static final Path DB = Path.of("purchase-constraints-test.db");
    private static final String URL = "jdbc:sqlite:" + DB + "?foreign_keys=on";

    static final long S = 1, N = 2;
    static final long ADMIN_S = 10, CASHIER_S = 11, ADMIN2_S = 12, ADMIN_N = 20, SYSADMIN = 1;
    static final long SA = 1, SB = 2, NA = 3, SC = 4;
    static final long P1 = 1, P2 = 2, P3 = 3, Q1 = 4, P5 = 5;

    static final Set<String> FINAL = Set.of("REJECTED", "CANCELED", "CLOSED");
    static final List<String> STATES = List.of("PENDING_APPROVAL", "APPROVED", "REJECTED", "CANCELED",
            "MESSAGE_GENERATED", "SENT", "CONFIRMED", "CLOSED");
    /** Figure 3.20 + approved deviations (D8 / D8b). */
    static final Map<String, Set<String>> ALLOWED = Map.of(
            "PENDING_APPROVAL", Set.of("APPROVED", "REJECTED", "CANCELED"),
            "APPROVED", Set.of("MESSAGE_GENERATED", "CANCELED"),
            "MESSAGE_GENERATED", Set.of("SENT", "CANCELED"),
            "SENT", Set.of("CONFIRMED", "CANCELED"),
            "CONFIRMED", Set.of("CLOSED", "CANCELED"),
            "REJECTED", Set.of(), "CANCELED", Set.of(), "CLOSED", Set.of());

    private Connection c;
    private final AtomicInteger numbers = new AtomicInteger();

    @BeforeEach
    void freshDatabase() throws Exception {
        Files.deleteIfExists(DB);
        Flyway.configure().dataSource(URL, null, null).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
        c = DriverManager.getConnection(URL);
        sql("INSERT INTO stores (store_id, name, status, created_at, guardian_enabled) VALUES "
                + "(1, 'Shini', 'ACTIVE', '2026-11-01T09:00:00', 1), (2, 'Neshan', 'ACTIVE', '2026-11-01T09:00:00', 0)");
        sql("INSERT INTO users (user_id, store_id, username, full_name, password_hash, role, created_at) VALUES "
                + "(1, NULL, 'sysadmin', 'System', 'x', 'SYSTEM_ADMIN', '2026-11-01T09:00:00'),"
                + "(10, 1, 'shini.admin', 'Ahmad', 'x', 'STORE_ADMIN', '2026-11-01T09:00:00'),"
                + "(11, 1, 'shini.cashier', 'Lina', 'x', 'CASHIER', '2026-11-01T09:00:00'),"
                + "(12, 1, 'shini.admin2', 'Sara', 'x', 'STORE_ADMIN', '2026-11-01T09:00:00'),"
                + "(20, 2, 'neshan.admin', 'Omar', 'x', 'STORE_ADMIN', '2026-11-01T09:00:00')");
        sql("INSERT INTO suppliers (supplier_id, store_id, name, whatsapp_number, active) VALUES "
                + "(1, 1, 'Dairy Co', '970599000001', 1), (2, 1, 'Bakery Co', NULL, 1), "
                + "(3, 2, 'Juice Co', '970599000003', 1), (4, 1, 'Old Co', NULL, 0)");
        sql("""
                INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price,
                                      quantity, min_stock, created_at, updated_at, cost_price) VALUES
                  (1, 1, 1, 'Milk', '11111111', 'dairy', 5, 20, 5, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 3.2),
                  (2, 1, 1, 'Labneh', '22222222', 'dairy', 14, 3, 4, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 9.5),
                  (3, 1, 2, 'Bread', '33333333', 'bakery', 2, 3, 1, '2026-11-01T09:00:00', '2026-11-01T09:00:00', NULL),
                  (4, 2, 3, 'Juice', '44444444', 'beverages', 8, 9, 2, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 5),
                  (5, 1, 4, 'Old item', '55555555', 'other', 3, 1, 2, '2026-11-01T09:00:00', '2026-11-01T09:00:00', 1)""");
    }

    @AfterEach
    void cleanUp() throws Exception {
        try {
            assertReservationInvariant();
        } finally {
            c.close();
            Files.deleteIfExists(DB);
        }
    }

    // ── helpers (direct SQL, as a buggy program or a person with the file could do) ──

    private void sql(String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private long one(String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private String text(String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private long newOrder(long store, long supplier, long user, String source) throws SQLException {
        return newOrder(store, supplier, user, source, "PO-" + String.format("%06d", numbers.incrementAndGet()),
                UUID.randomUUID().toString());
    }

    private long newOrder(long store, long supplier, long user, String source, String number, String requestId)
            throws SQLException {
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO purchase_orders (store_id, order_number, supplier_id, supplier_name, status, source,
                    created_by, created_at, client_request_id, request_hash)
                VALUES (?, ?, ?, 'Supplier', 'PENDING_APPROVAL', ?, ?, '2026-11-24T10:00:00', ?, ?)""")) {
            p.setLong(1, store);
            p.setString(2, number);
            p.setLong(3, supplier);
            p.setString(4, source);
            p.setLong(5, user);
            p.setString(6, requestId);
            p.setString(7, "a".repeat(64));
            p.executeUpdate();
        }
        return one("SELECT last_insert_rowid()");
    }

    private void addItem(long order, long store, long product) throws SQLException {
        addItem(order, store, product, store, product);
    }

    private void addItem(long order, long store, long product, Long productStore, Long open) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("""
                INSERT INTO purchase_order_items (order_id, store_id, product_id, product_store_id, product_name,
                    barcode, quantity, basis, calc_json, open_product_id)
                VALUES (?, ?, ?, ?, 'Item', '00000000', 5, 'MANUAL', '{}', ?)""")) {
            p.setLong(1, order);
            p.setLong(2, store);
            p.setLong(3, product);
            p.setObject(4, productStore);
            p.setObject(5, open);
            p.executeUpdate();
        }
    }

    private void update(long order, String set) throws SQLException {
        sql("UPDATE purchase_orders SET " + set + ", version = version + 1 WHERE order_id = " + order);
    }

    private void approve(long o, long user) throws SQLException {
        update(o, "status = 'APPROVED', approved_by = " + user + ", approved_at = '2026-11-24T11:00:00'");
    }

    private void reject(long o, long user, String reason) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE purchase_orders SET status = 'REJECTED', rejected_by = ?, "
                + "rejected_at = '2026-11-24T11:00:00', rejection_reason = ?, version = version + 1 WHERE order_id = ?")) {
            p.setLong(1, user);
            p.setString(2, reason);
            p.setLong(3, o);
            p.executeUpdate();
        }
    }

    private void cancel(long o, long user, String reason, int afterContact) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("UPDATE purchase_orders SET status = 'CANCELED', canceled_by = ?, "
                + "canceled_at = '2026-11-24T12:00:00', cancel_reason = ?, canceled_after_contact = ?, "
                + "version = version + 1 WHERE order_id = ?")) {
            p.setLong(1, user);
            p.setString(2, reason);
            p.setInt(3, afterContact);
            p.setLong(4, o);
            p.executeUpdate();
        }
    }

    private void message(long o, long user) throws SQLException {
        update(o, "status = 'MESSAGE_GENERATED', message_text = 'Order', message_language = 'ar', "
                + "message_whatsapp = '970599000001', message_generated_by = " + user
                + ", message_generated_at = '2026-11-24T12:00:00'");
    }

    private void markSent(long o, long user) throws SQLException {
        update(o, "status = 'SENT', sent_marked_by = " + user + ", sent_marked_at = '2026-11-24T12:30:00'");
    }

    private void confirm(long o, long user) throws SQLException {
        update(o, "status = 'CONFIRMED', confirmation_recorded_by = " + user
                + ", confirmation_recorded_at = '2026-11-24T13:00:00'");
    }

    private void close(long o, long user) throws SQLException {
        update(o, "status = 'CLOSED', closed_by = " + user + ", closed_at = '2026-11-25T09:00:00'");
    }

    /** The order's status after applying the legitimate path from PENDING_APPROVAL to {@code state}. */
    private long orderIn(String state, long product) throws SQLException {
        long o = newOrder(S, SA, ADMIN_S, "MANUAL");
        addItem(o, S, product);
        switch (state) {
            case "PENDING_APPROVAL" -> { }
            case "APPROVED" -> approve(o, ADMIN_S);
            case "REJECTED" -> reject(o, ADMIN_S, "Not needed");
            case "CANCELED" -> cancel(o, ADMIN_S, "Changed plan", 0);
            case "MESSAGE_GENERATED" -> { approve(o, ADMIN_S); message(o, ADMIN_S); }
            case "SENT" -> { approve(o, ADMIN_S); message(o, ADMIN_S); markSent(o, ADMIN_S); }
            case "CONFIRMED" -> { approve(o, ADMIN_S); message(o, ADMIN_S); markSent(o, ADMIN_S); confirm(o, ADMIN_S); }
            case "CLOSED" -> { approve(o, ADMIN_S); message(o, ADMIN_S); markSent(o, ADMIN_S); confirm(o, ADMIN_S);
                close(o, ADMIN_S); }
            default -> throw new IllegalArgumentException(state);
        }
        assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).isEqualTo(state);
        return o;
    }

    /** Moves an order to {@code target} with exactly the columns that target needs. */
    private void moveTo(long o, String from, String target) throws SQLException {
        switch (target) {
            case "APPROVED" -> approve(o, ADMIN_S);
            case "REJECTED" -> reject(o, ADMIN_S, "Not needed");
            case "CANCELED" -> cancel(o, ADMIN_S, "Changed plan", Set.of("SENT", "CONFIRMED").contains(from) ? 1 : 0);
            case "MESSAGE_GENERATED" -> message(o, ADMIN_S);
            case "SENT" -> markSent(o, ADMIN_S);
            case "CONFIRMED" -> confirm(o, ADMIN_S);
            case "CLOSED" -> close(o, ADMIN_S);
            case "PENDING_APPROVAL" -> update(o, "status = 'PENDING_APPROVAL'");
            default -> throw new IllegalArgumentException(target);
        }
    }

    private void refused(Executable action, String reason) {
        assertThatThrownBy(action::execute).isInstanceOf(SQLException.class).hasMessageContaining(reason);
    }

    /** Refused for any of these reasons (several BEFORE triggers may apply; SQLite does not fix their order). */
    private void refusedAny(Executable action, String... reasons) {
        assertThatThrownBy(action::execute).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(e.getMessage()).containsAnyOf(reasons));
    }

    /** Open order ⇒ every item reserved; final order ⇒ no item reserved. */
    private void assertReservationInvariant() throws SQLException {
        assertThat(one("""
                SELECT COUNT(*) FROM purchase_order_items i JOIN purchase_orders o ON o.order_id = i.order_id
                WHERE (o.status IN ('REJECTED','CANCELED','CLOSED') AND i.open_product_id IS NOT NULL)
                   OR (o.status NOT IN ('REJECTED','CANCELED','CLOSED') AND i.open_product_id IS NULL)"""))
                .as("reservation invariant").isZero();
    }

    // ── A: reasons can never be NULL, empty or blank ────────────────────────────────

    @Test
    void rejectionNeedsARealReason() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        refused(() -> reject(o, ADMIN_S, null), "CHECK");
        refused(() -> reject(o, ADMIN_S, ""), "CHECK");
        refused(() -> reject(o, ADMIN_S, "   "), "CHECK");
        refused(() -> reject(o, ADMIN_S, " \t\n\r  "), "CHECK");
        refused(() -> reject(o, ADMIN_S, "ab"), "CHECK");
        refused(() -> reject(o, ADMIN_S, "x".repeat(301)), "CHECK");
        assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).isEqualTo("PENDING_APPROVAL");
        reject(o, ADMIN_S, "  Too expensive  ");
        assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).isEqualTo("REJECTED");
    }

    @Test
    void cancellationNeedsARealReason() throws Exception {
        long o = orderIn("APPROVED", P1);
        refused(() -> cancel(o, ADMIN_S, null, 0), "CHECK");
        refused(() -> cancel(o, ADMIN_S, "", 0), "CHECK");
        refused(() -> cancel(o, ADMIN_S, "    ", 0), "CHECK");
        refused(() -> cancel(o, ADMIN_S, "\t\t", 0), "CHECK");
        cancel(o, ADMIN_S, "Supplier closed", 0);
        assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).isEqualTo("CANCELED");
    }

    @Test
    void aReasonOnAnOrderThatIsNotRejectedOrCanceledIsRefused() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        refused(() -> update(o, "rejection_reason = 'Sneaky'"), "CHECK");
        refused(() -> update(o, "cancel_reason = 'Sneaky'"), "CHECK");
    }

    // ── B: approval checks (T8), NULL-safe ──────────────────────────────────────────

    @Test
    void approvalNeedsAtLeastOneValidItem() throws Exception {
        long o = newOrder(S, SA, ADMIN_S, "MANUAL");
        refused(() -> approve(o, ADMIN_S), "PO_APPROVE_INVALID");      // zero items
        addItem(o, S, P1);
        approve(o, ADMIN_S);
        assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).isEqualTo("APPROVED");
    }

    @Test
    void approvalIsRefusedWhenAProductMovedToAnotherSupplier() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        sql("UPDATE products SET supplier_id = 2 WHERE product_id = 1"); // legitimate product edit
        refused(() -> approve(o, ADMIN_S), "PO_APPROVE_INVALID");
        sql("UPDATE products SET supplier_id = 1 WHERE product_id = 1");
        approve(o, ADMIN_S);
        sql("UPDATE products SET supplier_id = 2 WHERE product_id = 1"); // after approval: allowed, no effect
        assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).isEqualTo("APPROVED");
    }

    @Test
    void approvalIsRefusedForAnInactiveSupplier() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        sql("UPDATE suppliers SET active = 0 WHERE supplier_id = 1");
        refused(() -> approve(o, ADMIN_S), "PO_APPROVE_INVALID");
        // rejecting and canceling stay possible
        reject(o, ADMIN_S, "Supplier stopped");
    }

    @Test
    void messageGenerationIsRefusedForAnInactiveSupplier() throws Exception {
        long o = orderIn("APPROVED", P1);
        sql("UPDATE suppliers SET active = 0 WHERE supplier_id = 1");
        refused(() -> message(o, ADMIN_S), "PO_SUPPLIER_INACTIVE");
        cancel(o, ADMIN_S, "Supplier stopped", 0);
    }

    // ── C / D: product deletion and item integrity ──────────────────────────────────

    @Test
    void aDirectUpdateCannotPretendThatAProductWasDeleted() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        refused(() -> sql("UPDATE purchase_order_items SET product_id = NULL, product_store_id = NULL "
                + "WHERE order_id = " + o), "POI_UPDATE_NOT_ALLOWED");
        long o2 = orderIn("APPROVED", P2);
        refused(() -> sql("UPDATE purchase_order_items SET product_id = NULL, product_store_id = NULL "
                + "WHERE order_id = " + o2), "POI_UPDATE_NOT_ALLOWED");
        assertThat(one("SELECT COUNT(*) FROM purchase_order_items WHERE product_id IS NULL")).isZero();
    }

    @Test
    void deletingAProductInAPendingOrderKeepsTheItemAndBlocksApprovalUntilReplaced() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        sql("DELETE FROM products WHERE product_id = 1"); // the existing Delete Product feature
        assertThat(text("SELECT quote(product_id) || '|' || quote(product_store_id) || '|' || store_id || '|' "
                + "|| product_name || '|' || quantity || '|' || open_product_id FROM purchase_order_items WHERE order_id = " + o))
                .isEqualTo("NULL|NULL|1|Item|5|1");
        refused(() -> approve(o, ADMIN_S), "PO_APPROVE_INVALID");           // item without product
        sql("DELETE FROM purchase_order_items WHERE order_id = " + o);      // edit: remove the line
        refused(() -> approve(o, ADMIN_S), "PO_APPROVE_INVALID");           // empty order: never approved
        addItem(o, S, P2);                                                   // valid replacement
        approve(o, ADMIN_S);
        assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).isEqualTo("APPROVED");
    }

    @Test
    void productsInApprovedOrFinalOrdersCanStillBeDeleted() throws Exception {
        long approved = orderIn("SENT", P1);
        long closed = orderIn("CLOSED", P2);
        sql("INSERT INTO sales (sale_id, store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, "
                + "total, created_at, updated_at, completed_at) VALUES (1, 1, 11, 'INV-000001', 'COMPLETED', 14, 0, 16, "
                + "2.24, 16.24, '2026-11-20T10:00:00', '2026-11-20T10:00:00', '2026-11-20T10:00:00')");
        sql("INSERT INTO sale_items (sale_item_id, sale_id, product_id, product_name, barcode, quantity, unit_price, "
                + "line_total, returned_quantity, unit_cost) VALUES (1, 1, 2, 'Labneh', '22222222', 1, 14, 14, 0, 9.5)");
        assertDoesNotThrow(() -> sql("DELETE FROM products WHERE product_id IN (1, 2)"));
        assertThat(one("SELECT COUNT(*) FROM purchase_order_items WHERE product_id IS NULL")).isEqualTo(2);
        assertThat(one("SELECT COUNT(*) FROM sale_items WHERE product_id IS NULL")).isEqualTo(1);
        // the approved order continues its life
        confirm(approved, ADMIN_S);
        close(approved, ADMIN_S);
        assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + closed)).isEqualTo("CLOSED");
        // a new product never gets an old id (AUTOINCREMENT), so an old reservation cannot clash
        sql("INSERT INTO products (store_id, supplier_id, name, barcode, category, selling_price, quantity, min_stock, "
                + "created_at, updated_at) VALUES (1, 1, 'New', '99999999', 'dairy', 1, 1, 1, '2026-11-24T10:00:00', "
                + "'2026-11-24T10:00:00')");
        assertThat(one("SELECT MAX(product_id) FROM products")).isGreaterThan(5);
    }

    // ── reservations (duplicate-order protection) ───────────────────────────────────

    @Test
    void aProductCanBeInOnlyOneOpenOrderOfTheStore() throws Exception {
        orderIn("PENDING_APPROVAL", P1);
        long o2 = newOrder(S, SA, ADMIN_S, "MANUAL");
        refused(() -> addItem(o2, S, P1), "UNIQUE");
        addItem(o2, S, P2);
    }

    @Test
    void aPendingOrderCannotReleaseOrMoveAReservationByEditing() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        refused(() -> sql("UPDATE purchase_order_items SET open_product_id = NULL WHERE order_id = " + o),
                "POI_UPDATE_NOT_ALLOWED");
        refused(() -> sql("UPDATE purchase_order_items SET open_product_id = 2 WHERE order_id = " + o),
                "POI_UPDATE_NOT_ALLOWED");
        refused(() -> sql("UPDATE purchase_order_items SET product_id = 2, product_store_id = 1, open_product_id = 2 "
                + "WHERE order_id = " + o), "POI_UPDATE_NOT_ALLOWED");
        refused(() -> sql("UPDATE purchase_order_items SET calc_json = '{\"x\":1}' WHERE order_id = " + o),
                "POI_UPDATE_NOT_ALLOWED");
        refused(() -> addItem(o, S, P2, 1L, null), "POI_INSERT_INVALID");   // an item that reserves nothing
        refused(() -> addItem(o, S, P2, 1L, 1L), "POI_INSERT_INVALID");     // reserving another product
        sql("UPDATE purchase_order_items SET quantity = 7 WHERE order_id = " + o); // the only allowed item edit
        assertThat(one("SELECT quantity FROM purchase_order_items WHERE order_id = " + o)).isEqualTo(7);
    }

    @Test
    void anEditThatFailsLeavesEveryReservationAsItWas() throws Exception {
        long other = orderIn("PENDING_APPROVAL", P2);              // P2 reserved elsewhere
        long o = orderIn("PENDING_APPROVAL", P1);
        c.setAutoCommit(false);
        sql("DELETE FROM purchase_order_items WHERE order_id = " + o); // remove P1 …
        refused(() -> addItem(o, S, P2), "UNIQUE");                    // … add P2: already reserved
        c.rollback();                                                   // the service rolls the whole edit back
        c.setAutoCommit(true);
        assertThat(one("SELECT open_product_id FROM purchase_order_items WHERE order_id = " + o)).isEqualTo(P1);
        assertThat(one("SELECT open_product_id FROM purchase_order_items WHERE order_id = " + other)).isEqualTo(P2);
    }

    @Test
    void anEditThatSucceedsMovesTheReservationAtomically() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        c.setAutoCommit(false);
        sql("DELETE FROM purchase_order_items WHERE order_id = " + o);
        addItem(o, S, P2);
        update(o, "note = 'edited'");
        c.commit();
        c.setAutoCommit(true);
        long o2 = newOrder(S, SA, ADMIN_S, "MANUAL");
        addItem(o2, S, P1);                                        // P1 is free again
        refused(() -> addItem(newOrder(S, SA, ADMIN_S, "MANUAL"), S, P2), "UNIQUE");
    }

    @Test
    void reachingAFinalStateReleasesEveryReservation() throws Exception {
        for (String from : List.of("PENDING_APPROVAL", "APPROVED", "MESSAGE_GENERATED", "SENT", "CONFIRMED")) {
            for (String target : ALLOWED.get(from)) {
                if (!FINAL.contains(target)) continue;
                long o = orderIn(from, P1);
                moveTo(o, from, target);
                assertThat(one("SELECT COUNT(*) FROM purchase_order_items WHERE order_id = " + o
                        + " AND open_product_id IS NOT NULL")).as(from + " → " + target).isZero();
                long again = newOrder(S, SA, ADMIN_S, "MANUAL");        // the product can be ordered again
                addItem(again, S, P1);
                cancel(again, ADMIN_S, "Test cleanup", 0);
            }
        }
    }

    @Test
    void aFinalOrderCannotBeReopenedOrReReserved() throws Exception {
        long o = orderIn("CLOSED", P1);
        refused(() -> sql("UPDATE purchase_order_items SET open_product_id = 1 WHERE order_id = " + o),
                "POI_UPDATE_NOT_ALLOWED");
        refusedAny(() -> update(o, "status = 'PENDING_APPROVAL'"), "PO_FINAL", "PO_INVALID_TRANSITION");
        refused(() -> update(o, "note = 'x'"), "PO_FINAL");
        refused(() -> addItem(o, S, P2), "POI_INSERT_INVALID");
        refused(() -> sql("DELETE FROM purchase_order_items WHERE order_id = " + o), "POI_DELETE_NOT_ALLOWED");
    }

    @Test
    void itemsOfAnApprovedOrderAreLocked() throws Exception {
        long o = orderIn("APPROVED", P1);
        refused(() -> sql("UPDATE purchase_order_items SET quantity = 9 WHERE order_id = " + o), "POI_UPDATE_NOT_ALLOWED");
        refused(() -> sql("DELETE FROM purchase_order_items WHERE order_id = " + o), "POI_DELETE_NOT_ALLOWED");
        refused(() -> addItem(o, S, P2), "POI_INSERT_INVALID");
    }

    // ── transitions (T5 and friends) ────────────────────────────────────────────────

    @Test
    void everyPairOfStatesFollowsTheStateDiagram() throws Exception {
        long product = 100;
        for (String from : STATES) {
            for (String to : STATES) {
                if (from.equals(to)) continue;
                // a fresh product per order so reservations never interfere
                sql("INSERT INTO products (product_id, store_id, supplier_id, name, barcode, category, selling_price, "
                        + "quantity, min_stock, created_at, updated_at) VALUES (" + product + ", 1, 1, 'T" + product
                        + "', 'B" + product + "', 'dairy', 1, 1, 1, '2026-11-24T10:00:00', '2026-11-24T10:00:00')");
                long o = orderIn(from, product++);
                String label = from + " → " + to;
                if (ALLOWED.get(from).contains(to)) {
                    assertDoesNotThrow(() -> moveTo(o, from, to), label);
                    assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).as(label).isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> moveTo(o, from, to)).as(label).isInstanceOf(SQLException.class)
                            .satisfies(e -> assertThat(e.getMessage()).containsAnyOf("PO_INVALID_TRANSITION",
                                    "PO_FINAL", "PO_FIELD_SET_ONCE", "CHECK"));
                    assertThat(text("SELECT status FROM purchase_orders WHERE order_id = " + o)).as(label).isEqualTo(from);
                }
            }
        }
    }

    @Test
    void cancellationAfterSupplierContactIsAlwaysFlaggedHonestly() throws Exception {
        long sent = orderIn("SENT", P1);
        refused(() -> cancel(sent, ADMIN_S, "Changed plan", 0), "PO_CANCEL_FLAG");
        cancel(sent, ADMIN_S, "Supplier too slow", 1);
        long approved = orderIn("APPROVED", P2);
        refused(() -> cancel(approved, ADMIN_S, "Changed plan", 1), "PO_CANCEL_FLAG");
        cancel(approved, ADMIN_S, "Changed plan", 0);
        assertThat(one("SELECT canceled_after_contact FROM purchase_orders WHERE order_id = " + sent)).isEqualTo(1);
    }

    @Test
    void recordedFactsCannotBeRewritten() throws Exception {
        long o = orderIn("SENT", P1);
        refused(() -> update(o, "message_text = 'Different text'"), "PO_FIELD_SET_ONCE");
        refused(() -> update(o, "message_whatsapp = '970599999999'"), "PO_FIELD_SET_ONCE");
        refused(() -> update(o, "approved_by = 12"), "PO_FIELD_SET_ONCE");
        refused(() -> update(o, "sent_marked_at = '2026-11-30T00:00:00'"), "PO_FIELD_SET_ONCE");
    }

    @Test
    void identityAndVersionRulesHold() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        refused(() -> update(o, "supplier_id = 2"), "PO_IDENTITY_IMMUTABLE");
        refused(() -> update(o, "store_id = 2"), "PO_IDENTITY_IMMUTABLE");
        refused(() -> update(o, "order_number = 'PO-999999'"), "PO_IDENTITY_IMMUTABLE");
        refused(() -> update(o, "source = 'GUARDIAN'"), "PO_IDENTITY_IMMUTABLE");
        refused(() -> sql("UPDATE purchase_orders SET note = 'no version' WHERE order_id = " + o), "PO_VERSION");
        refused(() -> sql("UPDATE purchase_orders SET note = 'x', version = version + 2 WHERE order_id = " + o),
                "PO_VERSION");
    }

    @Test
    void aMessageNeedsApprovalAndAValidNumber() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        refused(() -> update(o, "message_text = 'x', message_language = 'ar', message_whatsapp = '970599000001', "
                + "message_generated_by = 10, message_generated_at = '2026-11-24T12:00:00'"), "CHECK");
        long a = orderIn("APPROVED", P2);
        refused(() -> update(a, "status = 'MESSAGE_GENERATED'"), "CHECK"); // no message text
        for (String bad : List.of("0599000001", "+970599000001", "9705990", "9705990000011234", "970 599000001", "97059900000a")) {
            refused(() -> update(a, "status = 'MESSAGE_GENERATED', message_text = 'x', message_language = 'ar', "
                    + "message_whatsapp = '" + bad + "', message_generated_by = 10, "
                    + "message_generated_at = '2026-11-24T12:00:00'"), "CHECK");
        }
    }

    @Test
    void ordersEventsAndSuppliersAreNeverDeleted() throws Exception {
        long o = orderIn("PENDING_APPROVAL", P1);
        sql("INSERT INTO purchase_order_events (order_id, store_id, user_id, event_type, to_status, created_at) "
                + "VALUES (" + o + ", 1, 10, 'CREATED', 'PENDING_APPROVAL', '2026-11-24T10:00:00')");
        refused(() -> sql("DELETE FROM purchase_orders WHERE order_id = " + o), "PO_NO_DELETE");
        refused(() -> sql("UPDATE purchase_order_events SET event_type = 'APPROVED'"), "POE_APPEND_ONLY");
        refused(() -> sql("DELETE FROM purchase_order_events"), "POE_APPEND_ONLY");
        sql("INSERT INTO suppliers (supplier_id, store_id, name) VALUES (9, 1, 'Unused Co')");
        refused(() -> sql("DELETE FROM suppliers WHERE supplier_id = 9"), "SUPPLIER_NO_DELETE"); // even unused
        refusedAny(() -> sql("DELETE FROM suppliers WHERE supplier_id = 2"), "SUPPLIER_NO_DELETE", "FOREIGN KEY");
    }

    // ── creation rules ──────────────────────────────────────────────────────────────

    @Test
    void newOrdersStartCleanForAnActiveSupplier() throws Exception {
        refused(() -> newOrder(S, SC, ADMIN_S, "MANUAL"), "PO_INSERT_INVALID");      // inactive supplier
        refused(() -> sql("INSERT INTO purchase_orders (store_id, order_number, supplier_id, supplier_name, status, "
                + "source, created_by, created_at, client_request_id, request_hash, approved_by, approved_at) VALUES "
                + "(1, 'PO-000900', 1, 'X', 'APPROVED', 'MANUAL', 10, '2026-11-24T10:00:00', '"
                + UUID.randomUUID() + "', '" + "b".repeat(64) + "', 10, '2026-11-24T10:00:00')"), "PO_INSERT_INVALID");
        refused(() -> newOrder(S, SA, ADMIN_S, "AI"), "CHECK");
    }

    @Test
    void guardianOrdersNeedGuardianModeOn() throws Exception {
        assertDoesNotThrow(() -> newOrder(S, SA, ADMIN_S, "GUARDIAN"));       // Shini: Guardian ON
        refused(() -> newOrder(N, NA, ADMIN_N, "GUARDIAN"), "PO_INSERT_INVALID"); // Neshan: OFF
        assertDoesNotThrow(() -> newOrder(N, NA, ADMIN_N, "MANUAL"));        // manual purchasing stays available
    }

    @Test
    void anItemMustBeAProductOfTheOrdersSupplierAndStore() throws Exception {
        long o = newOrder(S, SA, ADMIN_S, "MANUAL");
        refused(() -> addItem(o, S, P3), "POI_INSERT_INVALID");                  // supplier SB
        refusedAny(() -> addItem(o, S, 999), "POI_INSERT_INVALID", "FOREIGN KEY"); // no such product
    }

    @Test
    void onlyStoreAdminsCanAppearOnOrdersAndHistory() throws Exception {
        refused(() -> newOrder(S, SA, CASHIER_S, "MANUAL"), "PO_ACTOR_NOT_STORE_ADMIN");
        long o = orderIn("PENDING_APPROVAL", P1);
        refused(() -> approve(o, CASHIER_S), "PO_ACTOR_NOT_STORE_ADMIN");
        refused(() -> sql("INSERT INTO purchase_order_events (order_id, store_id, user_id, event_type, created_at) "
                + "VALUES (" + o + ", 1, 11, 'APPROVED', '2026-11-24T10:00:00')"), "POE_ACTOR_NOT_STORE_ADMIN");
        approve(o, ADMIN2_S); // another Store Admin of the same store may approve
    }

    @Test
    void nothingCanLinkTwoStores() throws Exception {
        refusedAny(() -> newOrder(N, SA, ADMIN_N, "MANUAL"), "FOREIGN KEY", "PO_INSERT_INVALID"); // supplier of store 1
        refused(() -> newOrder(N, NA, ADMIN_S, "MANUAL"), "FOREIGN KEY");       // creator of store 1
        refusedAny(() -> newOrder(S, SA, SYSADMIN, "MANUAL"), "FOREIGN KEY", "PO_ACTOR_NOT_STORE_ADMIN"); // no store
        long o = orderIn("PENDING_APPROVAL", P1);
        refused(() -> approve(o, ADMIN_N), "FOREIGN KEY");                      // approver of store 2
        refused(() -> addItem(o, N, Q1), "POI_INSERT_INVALID");                 // item of store 2 into a store-1 order
        refused(() -> addItem(o, S, Q1), "POI_INSERT_INVALID");                 // product of store 2
        refused(() -> addItem(o, S, P2, 2L, P2), "POI_INSERT_INVALID");         // product store ≠ item store
        refused(() -> sql("INSERT INTO purchase_order_events (order_id, store_id, user_id, event_type, created_at) "
                + "VALUES (" + o + ", 2, 20, 'CREATED', '2026-11-24T10:00:00')"), "FOREIGN KEY");
        refused(() -> sql("INSERT INTO purchase_order_events (order_id, store_id, user_id, event_type, created_at) "
                + "VALUES (" + o + ", 1, 20, 'CREATED', '2026-11-24T10:00:00')"), "FOREIGN KEY");
        // a Neshan order with a Neshan product works; the same product in both stores never clashes
        long n = newOrder(N, NA, ADMIN_N, "MANUAL");
        addItem(n, N, Q1);
    }

    @Test
    void orderNumbersAndRequestIdsAreUniquePerStore() throws Exception {
        String id = UUID.randomUUID().toString();
        newOrder(S, SA, ADMIN_S, "MANUAL", "PO-000001", id);
        refused(() -> newOrder(S, SA, ADMIN_S, "MANUAL", "PO-000001", UUID.randomUUID().toString()), "UNIQUE");
        refused(() -> newOrder(S, SA, ADMIN_S, "MANUAL", "PO-000002", id), "UNIQUE");
        assertDoesNotThrow(() -> newOrder(N, NA, ADMIN_N, "MANUAL", "PO-000001", id)); // other store: independent
        refused(() -> newOrder(S, SA, ADMIN_S, "MANUAL", "PO-12", UUID.randomUUID().toString()), "CHECK");
        refused(() -> newOrder(S, SA, ADMIN_S, "MANUAL", "PO-00000X", UUID.randomUUID().toString()), "CHECK");
    }

    @Test
    void itemValuesAreChecked() throws Exception {
        long o = newOrder(S, SA, ADMIN_S, "MANUAL");
        refused(() -> sql("INSERT INTO purchase_order_items (order_id, store_id, product_id, product_store_id, "
                + "product_name, barcode, quantity, basis, calc_json, open_product_id) VALUES (" + o
                + ", 1, 1, 1, 'Milk', '1', 0, 'MANUAL', '{}', 1)"), "CHECK");           // quantity 0
        refused(() -> sql("INSERT INTO purchase_order_items (order_id, store_id, product_id, product_store_id, "
                + "product_name, barcode, quantity, basis, calc_json, open_product_id) VALUES (" + o
                + ", 1, 1, 1, 'Milk', '1', 2.5, 'MANUAL', '{}', 1)"), "CHECK");         // not a whole number
        refused(() -> sql("INSERT INTO purchase_order_items (order_id, store_id, product_id, product_store_id, "
                + "product_name, barcode, quantity, basis, calc_json, open_product_id) VALUES (" + o
                + ", 1, 1, 1, 'Milk', '1', 2, 'MANUAL', 'not json', 1)"), "CHECK");     // calc_json
        refused(() -> sql("INSERT INTO purchase_order_items (order_id, store_id, product_id, product_store_id, "
                + "product_name, barcode, quantity, suggested_quantity, basis, calc_json, open_product_id) VALUES (" + o
                + ", 1, 1, 1, 'Milk', '1', 2, 5, 'MANUAL', '{}', 1)"), "CHECK");        // MANUAL has no suggestion
    }
}
