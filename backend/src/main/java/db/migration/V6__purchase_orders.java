package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V6: Purchase Orders, Phase 1 — database structure and Supplier Management
 * (report UR-M13–UR-M15, SR26–SR29, Figures 3.13 and 3.20).
 *
 * <p>Only additions. Existing rows are never changed:
 * <ul>
 *   <li>{@code stores.next_purchase_order_number} (default 1) and
 *       {@code suppliers.active} (default 1 = every existing supplier stays active);</li>
 *   <li>three unique indexes on (id, store_id) of suppliers, products and users, so
 *       the new tables can use composite foreign keys: a purchase order, its items
 *       and its history can never point to a supplier, product or user of another
 *       store, even through a bug or direct SQL;</li>
 *   <li>new tables purchase_orders, purchase_order_items, purchase_order_events
 *       with CHECK constraints and triggers (state transitions, one open order per
 *       product, append-only history, no deletion of orders or suppliers).</li>
 * </ul>
 * In Phase 1 the application only manages suppliers; nothing creates purchase
 * orders yet.
 *
 * <p>Safety, as in V5 but stricter: before any change the database must pass
 * {@code PRAGMA integrity_check} and {@code PRAGMA foreign_key_check}; then a
 * backup copy is written to a NEW file (an existing file is never overwritten)
 * and checked (integrity, the same rows AND the same content hash in every
 * table). The change runs in one transaction and is rolled back if any old
 * table's rows or content changed.
 *
 * <p>CHECK constraints are written so they cannot pass through NULL (in SQLite
 * a CHECK whose result is NULL passes), and every trigger condition is wrapped
 * in {@code coalesce(..., 1)} so an unexpected NULL blocks the change instead of
 * letting it through.
 */
public class V6__purchase_orders extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V6__purchase_orders.class);

    static final String BACKUP_PREFIX = "clixsell-backup-before-v6-";

    /** Tables created by this version (excluded from the "old data unchanged" checks). */
    public static final List<String> NEW_TABLES = List.of("purchase_orders", "purchase_order_items", "purchase_order_events");

    /** Characters a reason may not consist of only: space, tab, LF, CR, no-break space. */
    private static final String BLANKS = "' ' || char(9, 10, 13, 160)";

    /** A required, trimmed text of 3–300 characters; never passes when NULL. */
    private static String reason(String column) {
        return "coalesce(" + column + " IS NOT NULL AND typeof(" + column + ") = 'text' AND length(" + column
                + ") <= 300 AND length(trim(" + column + ", " + BLANKS + ")) >= 3, 0)";
    }

    private static final String FINAL = "('REJECTED','CANCELED','CLOSED')";

    /** The schema, statement by statement (also read by the tests). */
    static final List<String> SCHEMA = List.of(
            // ── existing tables: additions only ─────────────────────────────────────────
            "ALTER TABLE stores ADD COLUMN next_purchase_order_number INTEGER NOT NULL DEFAULT 1 "
                    + "CHECK (typeof(next_purchase_order_number) = 'integer' AND next_purchase_order_number >= 1)",
            "ALTER TABLE suppliers ADD COLUMN active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1))",
            "CREATE UNIQUE INDEX ux_suppliers_id_store ON suppliers (supplier_id, store_id)",
            "CREATE UNIQUE INDEX ux_products_id_store ON products (product_id, store_id)",
            "CREATE UNIQUE INDEX ux_users_id_store ON users (user_id, store_id)",

            // ── purchase_orders ─────────────────────────────────────────────────────────
            """
            CREATE TABLE purchase_orders (
                order_id                 INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id                 INTEGER NOT NULL REFERENCES stores (store_id),
                order_number             TEXT    NOT NULL CHECK (order_number GLOB 'PO-[0-9][0-9][0-9][0-9][0-9][0-9]*'
                                                    AND order_number NOT GLOB 'PO-*[^0-9]*'),
                supplier_id              INTEGER NOT NULL,
                supplier_name            TEXT    NOT NULL CHECK (length(trim(supplier_name)) BETWEEN 2 AND 80),
                status                   TEXT    NOT NULL CHECK (status IN ('PENDING_APPROVAL','APPROVED','REJECTED',
                                                    'CANCELED','MESSAGE_GENERATED','SENT','CONFIRMED','CLOSED')),
                source                   TEXT    NOT NULL CHECK (source IN ('GUARDIAN','MANUAL')),
                note                     TEXT    CHECK (note IS NULL OR (typeof(note) = 'text' AND length(note) <= 500)),
                created_by               INTEGER NOT NULL,
                created_at               TEXT    NOT NULL CHECK (length(created_at) >= 16),
                approved_by              INTEGER,
                approved_at              TEXT,
                rejected_by              INTEGER,
                rejected_at              TEXT,
                rejection_reason         TEXT,
                canceled_by              INTEGER,
                canceled_at              TEXT,
                cancel_reason            TEXT,
                canceled_after_contact   INTEGER NOT NULL DEFAULT 0 CHECK (canceled_after_contact IN (0, 1)),
                message_text             TEXT    CHECK (message_text IS NULL OR
                                                    (typeof(message_text) = 'text' AND length(message_text) BETWEEN 1 AND 2000)),
                message_language         TEXT    CHECK (message_language IS NULL OR message_language IN ('ar','en')),
                message_whatsapp         TEXT    CHECK (message_whatsapp IS NULL OR (typeof(message_whatsapp) = 'text'
                                                    AND length(message_whatsapp) BETWEEN 8 AND 15
                                                    AND message_whatsapp NOT GLOB '*[^0-9]*'
                                                    AND substr(message_whatsapp, 1, 1) <> '0')),
                message_generated_by     INTEGER,
                message_generated_at     TEXT,
                sent_marked_by           INTEGER,
                sent_marked_at           TEXT,
                confirmation_recorded_by INTEGER,
                confirmation_recorded_at TEXT,
                confirmation_note        TEXT    CHECK (confirmation_note IS NULL OR
                                                    (typeof(confirmation_note) = 'text' AND length(confirmation_note) <= 300)),
                closed_by                INTEGER,
                closed_at                TEXT,
                client_request_id        TEXT    NOT NULL CHECK (length(client_request_id) = 36),
                request_hash             TEXT    NOT NULL CHECK (length(request_hash) = 64
                                                    AND request_hash NOT GLOB '*[^0-9a-f]*'),
                version                  INTEGER NOT NULL DEFAULT 0 CHECK (typeof(version) = 'integer' AND version >= 0),
                UNIQUE (store_id, order_number),
                UNIQUE (store_id, client_request_id),
                UNIQUE (order_id, store_id),
                FOREIGN KEY (supplier_id, store_id)              REFERENCES suppliers (supplier_id, store_id),
                FOREIGN KEY (created_by, store_id)               REFERENCES users (user_id, store_id),
                FOREIGN KEY (approved_by, store_id)              REFERENCES users (user_id, store_id),
                FOREIGN KEY (rejected_by, store_id)              REFERENCES users (user_id, store_id),
                FOREIGN KEY (canceled_by, store_id)              REFERENCES users (user_id, store_id),
                FOREIGN KEY (message_generated_by, store_id)     REFERENCES users (user_id, store_id),
                FOREIGN KEY (sent_marked_by, store_id)           REFERENCES users (user_id, store_id),
                FOREIGN KEY (confirmation_recorded_by, store_id) REFERENCES users (user_id, store_id),
                FOREIGN KEY (closed_by, store_id)                REFERENCES users (user_id, store_id),
                CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
                CHECK ((rejected_by IS NULL) = (rejected_at IS NULL)),
                CHECK ((canceled_by IS NULL) = (canceled_at IS NULL)),
                CHECK ((message_generated_by IS NULL) = (message_generated_at IS NULL)),
                CHECK ((sent_marked_by IS NULL) = (sent_marked_at IS NULL)),
                CHECK ((confirmation_recorded_by IS NULL) = (confirmation_recorded_at IS NULL)),
                CHECK ((closed_by IS NULL) = (closed_at IS NULL)),
                CHECK (status NOT IN ('APPROVED','MESSAGE_GENERATED','SENT','CONFIRMED','CLOSED') OR approved_at IS NOT NULL),
                CHECK (status <> 'REJECTED' OR (rejected_at IS NOT NULL AND %REJECT_REASON%)),
                CHECK (rejection_reason IS NULL OR status = 'REJECTED'),
                CHECK (rejected_at IS NULL OR status = 'REJECTED'),
                CHECK (status <> 'CANCELED' OR (canceled_at IS NOT NULL AND %CANCEL_REASON%)),
                CHECK (cancel_reason IS NULL OR status = 'CANCELED'),
                CHECK (canceled_at IS NULL OR status = 'CANCELED'),
                CHECK (status NOT IN ('MESSAGE_GENERATED','SENT','CONFIRMED','CLOSED') OR (message_text IS NOT NULL
                       AND message_whatsapp IS NOT NULL AND message_language IS NOT NULL AND message_generated_at IS NOT NULL)),
                CHECK (message_text IS NULL OR approved_at IS NOT NULL),
                CHECK ((message_text IS NULL) = (message_generated_at IS NULL)),
                CHECK (status NOT IN ('SENT','CONFIRMED','CLOSED') OR sent_marked_at IS NOT NULL),
                CHECK (sent_marked_at IS NULL OR message_text IS NOT NULL),
                CHECK (status NOT IN ('CONFIRMED','CLOSED') OR confirmation_recorded_at IS NOT NULL),
                CHECK (confirmation_recorded_at IS NULL OR sent_marked_at IS NOT NULL),
                CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
                CHECK (closed_at IS NULL OR status = 'CLOSED'),
                CHECK (canceled_after_contact = 0 OR (status = 'CANCELED' AND sent_marked_at IS NOT NULL)),
                CHECK (approved_at IS NULL OR rejected_at IS NULL)
            )""".replace("%REJECT_REASON%", reason("rejection_reason")).replace("%CANCEL_REASON%", reason("cancel_reason")),
            "CREATE INDEX ix_po_store_status ON purchase_orders (store_id, status, created_at)",

            // ── purchase_order_items ────────────────────────────────────────────────────
            """
            CREATE TABLE purchase_order_items (
                item_id             INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id            INTEGER NOT NULL,
                store_id            INTEGER NOT NULL,
                product_id          INTEGER,
                product_store_id    INTEGER,
                product_name        TEXT    NOT NULL CHECK (length(trim(product_name)) >= 1),
                barcode             TEXT    NOT NULL CHECK (length(trim(barcode)) >= 1),
                quantity            INTEGER NOT NULL CHECK (typeof(quantity) = 'integer' AND quantity BETWEEN 1 AND 100000),
                suggested_quantity  INTEGER CHECK (suggested_quantity IS NULL OR
                                        (typeof(suggested_quantity) = 'integer' AND suggested_quantity BETWEEN 1 AND 100000)),
                basis               TEXT    NOT NULL CHECK (basis IN ('SALES_RATE','MIN_STOCK_ONLY','MANUAL')),
                calc_json           TEXT    NOT NULL CHECK (json_valid(calc_json)),
                expected_unit_cost  REAL    CHECK (expected_unit_cost IS NULL OR
                                        (typeof(expected_unit_cost) IN ('real','integer') AND expected_unit_cost >= 0)),
                open_product_id     INTEGER,
                FOREIGN KEY (order_id, store_id) REFERENCES purchase_orders (order_id, store_id),
                FOREIGN KEY (product_id, product_store_id) REFERENCES products (product_id, store_id) ON DELETE SET NULL,
                CHECK ((product_id IS NULL) = (product_store_id IS NULL)),
                CHECK (product_store_id IS NULL OR product_store_id = store_id),
                CHECK (open_product_id IS NULL OR product_id IS NULL OR open_product_id = product_id),
                CHECK (basis <> 'MANUAL' OR suggested_quantity IS NULL),
                UNIQUE (order_id, product_id)
            )""",
            "CREATE UNIQUE INDEX ux_poi_open_product ON purchase_order_items (store_id, open_product_id) "
                    + "WHERE open_product_id IS NOT NULL",
            "CREATE INDEX ix_poi_order ON purchase_order_items (order_id)",
            "CREATE INDEX ix_poi_product ON purchase_order_items (store_id, product_id)",

            // ── purchase_order_events ───────────────────────────────────────────────────
            """
            CREATE TABLE purchase_order_events (
                event_id    INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id    INTEGER NOT NULL,
                store_id    INTEGER NOT NULL,
                user_id     INTEGER NOT NULL,
                event_type  TEXT    NOT NULL CHECK (event_type IN ('CREATED','EDITED','APPROVED','REJECTED','CANCELED',
                                'MESSAGE_GENERATED','MESSAGE_COPIED','WHATSAPP_OPEN_REQUESTED','MARKED_SENT',
                                'SUPPLIER_CONFIRMATION_RECORDED','CLOSED','SUPPLIER_CANCELLATION_RECORDED')),
                from_status TEXT,
                to_status   TEXT,
                details     TEXT    CHECK (details IS NULL OR json_valid(details)),
                created_at  TEXT    NOT NULL CHECK (length(created_at) >= 16),
                FOREIGN KEY (order_id, store_id) REFERENCES purchase_orders (order_id, store_id),
                FOREIGN KEY (user_id, store_id)  REFERENCES users (user_id, store_id)
            )""",
            "CREATE INDEX ix_poe_order ON purchase_order_events (order_id, event_id)",

            // ── triggers: purchase_orders ───────────────────────────────────────────────
            // T1: a new order starts clean, by a Store Admin, for an ACTIVE supplier of the store;
            //     a Guardian order only while Guardian Mode is on.
            """
            CREATE TRIGGER trg_po_insert BEFORE INSERT ON purchase_orders
            WHEN coalesce(
                    NEW.status IS NOT 'PENDING_APPROVAL' OR NEW.version IS NOT 0
                 OR NEW.approved_by IS NOT NULL OR NEW.rejected_by IS NOT NULL OR NEW.canceled_by IS NOT NULL
                 OR NEW.message_text IS NOT NULL OR NEW.message_whatsapp IS NOT NULL
                 OR NEW.message_generated_by IS NOT NULL OR NEW.sent_marked_by IS NOT NULL
                 OR NEW.confirmation_recorded_by IS NOT NULL OR NEW.closed_by IS NOT NULL
                 OR NEW.canceled_after_contact IS NOT 0
                 OR (SELECT active FROM suppliers WHERE supplier_id = NEW.supplier_id AND store_id = NEW.store_id) IS NOT 1
                 OR (NEW.source IS 'GUARDIAN'
                     AND (SELECT guardian_enabled FROM stores WHERE store_id = NEW.store_id) IS NOT 1), 1)
            BEGIN SELECT RAISE(ABORT, 'PO_INSERT_INVALID'); END""",
            // T2: identity never changes
            """
            CREATE TRIGGER trg_po_identity BEFORE UPDATE ON purchase_orders
            WHEN coalesce(
                    NEW.order_id IS NOT OLD.order_id OR NEW.store_id IS NOT OLD.store_id
                 OR NEW.supplier_id IS NOT OLD.supplier_id OR NEW.supplier_name IS NOT OLD.supplier_name
                 OR NEW.order_number IS NOT OLD.order_number OR NEW.source IS NOT OLD.source
                 OR NEW.client_request_id IS NOT OLD.client_request_id OR NEW.request_hash IS NOT OLD.request_hash
                 OR NEW.created_by IS NOT OLD.created_by OR NEW.created_at IS NOT OLD.created_at, 1)
            BEGIN SELECT RAISE(ABORT, 'PO_IDENTITY_IMMUTABLE'); END""",
            // T3: a final order never changes
            "CREATE TRIGGER trg_po_final BEFORE UPDATE ON purchase_orders WHEN coalesce(OLD.status IN " + FINAL + ", 1) "
                    + "BEGIN SELECT RAISE(ABORT, 'PO_FINAL'); END",
            // T4: every update raises the version by exactly one (optimistic locking cannot be skipped)
            "CREATE TRIGGER trg_po_version BEFORE UPDATE ON purchase_orders "
                    + "WHEN coalesce(NEW.version IS NOT OLD.version + 1, 1) "
                    + "BEGIN SELECT RAISE(ABORT, 'PO_VERSION'); END",
            // T5: the state diagram (Figure 3.20 + approved deviations D8 / D8b)
            """
            CREATE TRIGGER trg_po_transition BEFORE UPDATE ON purchase_orders
            WHEN NEW.status IS NOT OLD.status AND NOT coalesce(
                    (OLD.status IS 'PENDING_APPROVAL'  AND NEW.status IN ('APPROVED','REJECTED','CANCELED'))
                 OR (OLD.status IS 'APPROVED'          AND NEW.status IN ('MESSAGE_GENERATED','CANCELED'))
                 OR (OLD.status IS 'MESSAGE_GENERATED' AND NEW.status IN ('SENT','CANCELED'))
                 OR (OLD.status IS 'SENT'              AND NEW.status IN ('CONFIRMED','CANCELED'))
                 OR (OLD.status IS 'CONFIRMED'         AND NEW.status IN ('CLOSED','CANCELED')), 0)
            BEGIN SELECT RAISE(ABORT, 'PO_INVALID_TRANSITION'); END""",
            // T6: canceled after supplier contact ⇔ canceled from SENT or CONFIRMED
            """
            CREATE TRIGGER trg_po_cancel_flag BEFORE UPDATE ON purchase_orders
            WHEN NEW.status IS 'CANCELED' AND OLD.status IS NOT 'CANCELED'
             AND coalesce(NEW.canceled_after_contact IS NOT (OLD.status IN ('SENT','CONFIRMED')), 1)
            BEGIN SELECT RAISE(ABORT, 'PO_CANCEL_FLAG'); END""",
            // T7: what is recorded once is never changed
            """
            CREATE TRIGGER trg_po_set_once BEFORE UPDATE ON purchase_orders
            WHEN coalesce(
                    (OLD.approved_at IS NOT NULL AND (NEW.approved_at IS NOT OLD.approved_at
                        OR NEW.approved_by IS NOT OLD.approved_by))
                 OR (OLD.message_text IS NOT NULL AND (NEW.message_text IS NOT OLD.message_text
                        OR NEW.message_whatsapp IS NOT OLD.message_whatsapp OR NEW.message_language IS NOT OLD.message_language
                        OR NEW.message_generated_at IS NOT OLD.message_generated_at
                        OR NEW.message_generated_by IS NOT OLD.message_generated_by))
                 OR (OLD.sent_marked_at IS NOT NULL AND (NEW.sent_marked_at IS NOT OLD.sent_marked_at
                        OR NEW.sent_marked_by IS NOT OLD.sent_marked_by))
                 OR (OLD.confirmation_recorded_at IS NOT NULL AND (NEW.confirmation_recorded_at IS NOT OLD.confirmation_recorded_at
                        OR NEW.confirmation_recorded_by IS NOT OLD.confirmation_recorded_by
                        OR NEW.confirmation_note IS NOT OLD.confirmation_note)), 1)
            BEGIN SELECT RAISE(ABORT, 'PO_FIELD_SET_ONCE'); END""",
            // T8: approval needs ≥ 1 item, every item's product still exists, belongs to the order's
            //     store AND supplier, and the supplier is active (NULL-safe: a missing row blocks)
            """
            CREATE TRIGGER trg_po_approve BEFORE UPDATE ON purchase_orders
            WHEN NEW.status IS 'APPROVED' AND OLD.status IS 'PENDING_APPROVAL' AND coalesce(
                    NOT EXISTS (SELECT 1 FROM purchase_order_items WHERE order_id = NEW.order_id)
                 OR EXISTS (SELECT 1 FROM purchase_order_items i
                            LEFT JOIN products p ON p.product_id = i.product_id
                            WHERE i.order_id = NEW.order_id
                              AND (i.product_id IS NULL OR p.product_id IS NULL
                                   OR i.store_id IS NOT NEW.store_id
                                   OR p.store_id IS NOT NEW.store_id
                                   OR p.supplier_id IS NOT NEW.supplier_id))
                 OR (SELECT active FROM suppliers WHERE supplier_id = NEW.supplier_id AND store_id = NEW.store_id) IS NOT 1, 1)
            BEGIN SELECT RAISE(ABORT, 'PO_APPROVE_INVALID'); END""",
            // T9: generating the supplier message needs an active supplier
            """
            CREATE TRIGGER trg_po_message BEFORE UPDATE ON purchase_orders
            WHEN NEW.status IS 'MESSAGE_GENERATED' AND OLD.status IS 'APPROVED'
             AND (SELECT active FROM suppliers WHERE supplier_id = NEW.supplier_id AND store_id = NEW.store_id) IS NOT 1
            BEGIN SELECT RAISE(ABORT, 'PO_SUPPLIER_INACTIVE'); END""",
            // T10: orders are never deleted
            "CREATE TRIGGER trg_po_no_delete BEFORE DELETE ON purchase_orders BEGIN SELECT RAISE(ABORT, 'PO_NO_DELETE'); END",
            // T11: reaching a final state releases the order's product reservations (same statement)
            """
            CREATE TRIGGER trg_po_release AFTER UPDATE ON purchase_orders
            WHEN NEW.status IN %FINAL% AND OLD.status NOT IN %FINAL%
            BEGIN
                UPDATE purchase_order_items SET open_product_id = NULL
                 WHERE order_id = NEW.order_id AND open_product_id IS NOT NULL;
            END""".replace("%FINAL%", FINAL),
            // T18: every person recorded on an order is a Store Admin (the FK already checks the store)
            """
            CREATE TRIGGER trg_po_actor_insert BEFORE INSERT ON purchase_orders
            WHEN coalesce((SELECT role FROM users WHERE user_id = NEW.created_by) IS NOT 'STORE_ADMIN', 1)
            BEGIN SELECT RAISE(ABORT, 'PO_ACTOR_NOT_STORE_ADMIN'); END""",
            """
            CREATE TRIGGER trg_po_actor_update BEFORE UPDATE ON purchase_orders
            WHEN coalesce(
                    (NEW.approved_by IS NOT NULL AND (SELECT role FROM users WHERE user_id = NEW.approved_by) IS NOT 'STORE_ADMIN')
                 OR (NEW.rejected_by IS NOT NULL AND (SELECT role FROM users WHERE user_id = NEW.rejected_by) IS NOT 'STORE_ADMIN')
                 OR (NEW.canceled_by IS NOT NULL AND (SELECT role FROM users WHERE user_id = NEW.canceled_by) IS NOT 'STORE_ADMIN')
                 OR (NEW.message_generated_by IS NOT NULL
                     AND (SELECT role FROM users WHERE user_id = NEW.message_generated_by) IS NOT 'STORE_ADMIN')
                 OR (NEW.sent_marked_by IS NOT NULL
                     AND (SELECT role FROM users WHERE user_id = NEW.sent_marked_by) IS NOT 'STORE_ADMIN')
                 OR (NEW.confirmation_recorded_by IS NOT NULL
                     AND (SELECT role FROM users WHERE user_id = NEW.confirmation_recorded_by) IS NOT 'STORE_ADMIN')
                 OR (NEW.closed_by IS NOT NULL AND (SELECT role FROM users WHERE user_id = NEW.closed_by) IS NOT 'STORE_ADMIN'), 1)
            BEGIN SELECT RAISE(ABORT, 'PO_ACTOR_NOT_STORE_ADMIN'); END""",

            // ── triggers: purchase_order_items ──────────────────────────────────────────
            // T12: an item is added only to a PENDING order of the same store, for an existing product
            //      of that store AND of the order's supplier, and it reserves exactly that product
            """
            CREATE TRIGGER trg_poi_insert BEFORE INSERT ON purchase_order_items
            WHEN coalesce(
                    (SELECT status FROM purchase_orders WHERE order_id = NEW.order_id AND store_id = NEW.store_id)
                        IS NOT 'PENDING_APPROVAL'
                 OR NEW.product_id IS NULL
                 OR NEW.product_store_id IS NOT NEW.store_id
                 OR NEW.open_product_id IS NOT NEW.product_id
                 OR NOT EXISTS (SELECT 1 FROM products p JOIN purchase_orders o ON o.order_id = NEW.order_id
                                WHERE p.product_id = NEW.product_id AND p.store_id = NEW.store_id
                                  AND o.store_id = NEW.store_id AND p.supplier_id = o.supplier_id), 1)
            BEGIN SELECT RAISE(ABORT, 'POI_INSERT_INVALID'); END""",
            // T13: an item changes in exactly three allowed ways
            //  (a) only its quantity, while the order is PENDING_APPROVAL;
            //  (b) the product was really deleted (ON DELETE SET NULL): only product_id and
            //      product_store_id become NULL, and the product no longer exists — a direct
            //      UPDATE that only pretends a deletion is refused (the product still exists);
            //  (c) only the reservation is released, and the order is already final.
            """
            CREATE TRIGGER trg_poi_update BEFORE UPDATE ON purchase_order_items
            WHEN NOT coalesce(
                 (  (SELECT status FROM purchase_orders WHERE order_id = OLD.order_id) IS 'PENDING_APPROVAL'
                    AND NEW.item_id IS OLD.item_id AND NEW.order_id IS OLD.order_id AND NEW.store_id IS OLD.store_id
                    AND NEW.product_id IS OLD.product_id AND NEW.product_store_id IS OLD.product_store_id
                    AND NEW.product_name IS OLD.product_name AND NEW.barcode IS OLD.barcode
                    AND NEW.suggested_quantity IS OLD.suggested_quantity AND NEW.basis IS OLD.basis
                    AND NEW.calc_json IS OLD.calc_json AND NEW.expected_unit_cost IS OLD.expected_unit_cost
                    AND NEW.open_product_id IS OLD.open_product_id)
              OR (  OLD.product_id IS NOT NULL AND NEW.product_id IS NULL AND NEW.product_store_id IS NULL
                    AND NOT EXISTS (SELECT 1 FROM products WHERE product_id = OLD.product_id)
                    AND NEW.item_id IS OLD.item_id AND NEW.order_id IS OLD.order_id AND NEW.store_id IS OLD.store_id
                    AND NEW.product_name IS OLD.product_name AND NEW.barcode IS OLD.barcode
                    AND NEW.quantity IS OLD.quantity
                    AND NEW.suggested_quantity IS OLD.suggested_quantity AND NEW.basis IS OLD.basis
                    AND NEW.calc_json IS OLD.calc_json AND NEW.expected_unit_cost IS OLD.expected_unit_cost
                    AND NEW.open_product_id IS OLD.open_product_id)
              OR (  OLD.open_product_id IS NOT NULL AND NEW.open_product_id IS NULL
                    AND coalesce((SELECT status FROM purchase_orders WHERE order_id = OLD.order_id) IN %FINAL%, 0)
                    AND NEW.item_id IS OLD.item_id AND NEW.order_id IS OLD.order_id AND NEW.store_id IS OLD.store_id
                    AND NEW.product_id IS OLD.product_id AND NEW.product_store_id IS OLD.product_store_id
                    AND NEW.product_name IS OLD.product_name AND NEW.barcode IS OLD.barcode
                    AND NEW.quantity IS OLD.quantity
                    AND NEW.suggested_quantity IS OLD.suggested_quantity AND NEW.basis IS OLD.basis
                    AND NEW.calc_json IS OLD.calc_json AND NEW.expected_unit_cost IS OLD.expected_unit_cost), 0)
            BEGIN SELECT RAISE(ABORT, 'POI_UPDATE_NOT_ALLOWED'); END""".replace("%FINAL%", FINAL),
            // T14: an item is removed only while the order is PENDING_APPROVAL (editing the order)
            """
            CREATE TRIGGER trg_poi_delete BEFORE DELETE ON purchase_order_items
            WHEN coalesce((SELECT status FROM purchase_orders WHERE order_id = OLD.order_id) IS NOT 'PENDING_APPROVAL', 1)
            BEGIN SELECT RAISE(ABORT, 'POI_DELETE_NOT_ALLOWED'); END""",

            // ── triggers: purchase_order_events (append only, written by a Store Admin) ──
            "CREATE TRIGGER trg_poe_no_update BEFORE UPDATE ON purchase_order_events "
                    + "BEGIN SELECT RAISE(ABORT, 'POE_APPEND_ONLY'); END",
            "CREATE TRIGGER trg_poe_no_delete BEFORE DELETE ON purchase_order_events "
                    + "BEGIN SELECT RAISE(ABORT, 'POE_APPEND_ONLY'); END",
            """
            CREATE TRIGGER trg_poe_actor BEFORE INSERT ON purchase_order_events
            WHEN coalesce((SELECT role FROM users WHERE user_id = NEW.user_id) IS NOT 'STORE_ADMIN', 1)
            BEGIN SELECT RAISE(ABORT, 'POE_ACTOR_NOT_STORE_ADMIN'); END""",

            // T17: suppliers are never deleted (products and orders keep pointing to them)
            "CREATE TRIGGER trg_suppliers_no_delete BEFORE DELETE ON suppliers "
                    + "BEGIN SELECT RAISE(ABORT, 'SUPPLIER_NO_DELETE'); END"
    );

    /** VACUUM INTO (the backup) cannot run inside a transaction, so this migration manages its own. */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        checkBeforeChange(c);
        List<String> tables = tables(c);
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (String t : tables) columns.put(t, columns(c, t));
        Map<String, Long> rows = counts(c, tables);
        Map<String, String> hashes = hashes(c, columns);

        if (rows.values().stream().anyMatch(n -> n > 0)) {
            String backup = BACKUP_PREFIX
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")) + ".db";
            writeBackup(c, backup, rows, hashes, columns);
            log.info("Database backup written and checked: {}", Path.of(backup).toAbsolutePath());
        }

        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            for (String sql : SCHEMA) exec(c, sql);
            verifyAfterChange(c, rows, hashes, columns);
            c.commit();
            log.info("Purchase-order schema added; existing rows kept unchanged: {}", rows);
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(autoCommit);
        }
    }

    /**
     * Stops before ANY change (no backup, no schema) if the database is damaged or already
     * has broken references. Products whose supplier belongs to another store are only
     * reported: V6 does not touch the products table.
     */
    static void checkBeforeChange(Connection c) throws SQLException {
        String integrity = text(c, "PRAGMA integrity_check");
        if (!"ok".equalsIgnoreCase(integrity)) {
            throw new IllegalStateException("The database failed the integrity check (" + integrity
                    + "); nothing was changed");
        }
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) {
                throw new IllegalStateException("The database has broken references (table " + rs.getString(1)
                        + ", row " + rs.getLong(2) + " → " + rs.getString(3) + "); nothing was changed");
            }
        }
        long crossStore = count(c, "SELECT COUNT(*) FROM products p JOIN suppliers s ON s.supplier_id = p.supplier_id "
                + "WHERE s.store_id <> p.store_id");
        if (crossStore > 0) {
            log.warn("{} product(s) use a supplier of another store; V6 does not change them (report only)", crossStore);
        }
    }

    /**
     * Writes the backup to a NEW file and checks it: integrity "ok" and, for every table,
     * the same number of rows and the same content hash as the database. An existing
     * file is never overwritten (checked here, and VACUUM INTO refuses a non-empty file).
     */
    static void writeBackup(Connection c, String file, Map<String, Long> rows, Map<String, String> hashes,
                            Map<String, List<String>> columns) throws SQLException {
        if (Files.exists(Path.of(file))) {
            throw new IllegalStateException("Backup file " + file + " already exists; it was not touched and "
                    + "the database was not changed");
        }
        exec(c, "VACUUM INTO '" + file.replace("'", "''") + "'");
        try (Connection b = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            String integrity = text(b, "PRAGMA integrity_check");
            if (!"ok".equalsIgnoreCase(integrity)) {
                throw new IllegalStateException("Backup " + file + " failed the integrity check (" + integrity
                        + "); the database was not changed");
            }
            Map<String, Long> copiedRows = counts(b, rows.keySet());
            if (!copiedRows.equals(rows)) {
                throw new IllegalStateException("Backup " + file + " does not hold every row " + rows + " → "
                        + copiedRows + "; the database was not changed");
            }
            if (!hashes(b, columns).equals(hashes)) {
                throw new IllegalStateException("Backup " + file + " content differs from the database; "
                        + "the database was not changed");
            }
        }
    }

    /** After the change (still inside the transaction): old data identical, defaults right, new tables empty. */
    private static void verifyAfterChange(Connection c, Map<String, Long> rows, Map<String, String> hashes,
                                          Map<String, List<String>> columns) throws SQLException {
        Map<String, Long> rowsNow = counts(c, rows.keySet());
        if (!rowsNow.equals(rows)) {
            throw new IllegalStateException("Row counts changed " + rows + " → " + rowsNow + "; rolled back");
        }
        if (!hashes(c, columns).equals(hashes)) {
            throw new IllegalStateException("Existing data changed during the migration; rolled back");
        }
        if (count(c, "SELECT COUNT(*) FROM suppliers WHERE active IS NOT 1") != 0
                || count(c, "SELECT COUNT(*) FROM stores WHERE next_purchase_order_number IS NOT 1") != 0) {
            throw new IllegalStateException("New columns did not get their defaults; rolled back");
        }
        for (String t : NEW_TABLES) {
            if (count(c, "SELECT COUNT(*) FROM " + t) != 0) {
                throw new IllegalStateException("New table " + t + " is not empty; rolled back");
            }
        }
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) throw new IllegalStateException("Broken references after the change; rolled back");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    /** Every table of the database except SQLite's and Flyway's own. */
    static List<String> tables(Connection c) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' "
                     + "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history' ORDER BY name")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    static List<String> columns(Connection c, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM pragma_table_info('" + table.replace("'", "''")
                     + "') ORDER BY cid")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    static Map<String, Long> counts(Connection c, Iterable<String> tables) throws SQLException {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : tables) m.put(t, count(c, "SELECT COUNT(*) FROM \"" + t + "\""));
        return m;
    }

    /**
     * SHA-256 of every value (type and text) of the given columns of each table, in rowid
     * order. Only the columns that existed before the change are read, so the new columns
     * do not count as "changed data".
     */
    static Map<String, String> hashes(Connection c, Map<String, List<String>> columns) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : columns.entrySet()) {
            MessageDigest md = sha256();
            StringBuilder select = new StringBuilder("SELECT ");
            for (int i = 0; i < e.getValue().size(); i++) {
                String col = "\"" + e.getValue().get(i) + "\"";
                if (i > 0) select.append(", ");
                select.append("typeof(").append(col).append("), quote(").append(col).append(")");
            }
            select.append(" FROM \"").append(e.getKey()).append("\" ORDER BY rowid");
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(select.toString())) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    for (int i = 1; i <= n; i++) {
                        String v = rs.getString(i);
                        md.update((v == null ? "\u0000" : v).getBytes(StandardCharsets.UTF_8));
                        md.update((byte) 0x1f);
                    }
                    md.update((byte) 0x1e);
                }
            }
            m.put(e.getKey(), HexFormat.of().formatHex(md.digest()));
        }
        return m;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
