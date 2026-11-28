package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * V7: POS store operations — cash drawers and shifts, cash movements, product favorites,
 * piece / carton / box packages, cash-only invoice rounding, and CHEQUE payments with their
 * lifecycle, refunds and cash payouts (management decisions D1–D7).
 *
 * <p>Validated first as a prototype on disposable V6 databases (see V7MigrationValidationTest);
 * the schema below is that validated schema, statement for statement.
 *
 * <p>Order of operations (each step stops everything if it fails):
 * <ol>
 *   <li>Pre-flight on the untouched database: integrity_check, foreign_key_check, schema at V6, at
 *       most one payment per sale, only known payment methods, no V7 object present.</li>
 *   <li>Snapshot: every old table's columns, row count and content hash; every index and trigger.</li>
 *   <li>Backup OUTSIDE any transaction (SQLite refuses VACUUM INTO inside one): a NEW file next to
 *       the database, name with time + random suffix, never overwriting; then verified (integrity,
 *       rows, content hash). A backup failure leaves the database untouched.</li>
 *   <li>One transaction: all schema statements, then verification (old rows and content identical,
 *       old indexes and triggers present, new tables empty, defaults, foreign keys, quick_check).
 *       Any error rolls back: the database is exactly as before and the backup is kept.</li>
 * </ol>
 *
 * <p>Money: new amounts are INTEGER minor units (agorot). Legacy REAL columns are kept and never
 * rewritten; conversion is {@code CAST(round(x * 100) AS INTEGER)} in SQL and
 * {@code BigDecimal.valueOf(x).movePointRight(2).setScale(0, HALF_UP)} in Java.
 */
public class V7__store_operations extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V7__store_operations.class);

    public static final String BACKUP_PREFIX = "clixsell-backup-before-v7-";

    /** Tables created by V7 (must be absent before, empty after). */
    public static final List<String> NEW_TABLES = List.of("cash_drawers", "cash_shifts", "product_packages",
            "product_favorites", "cheques", "cheque_events", "cheque_refunds", "cash_movements");

    private static final String BLANKS = "' ' || char(9, 10, 13, 160)";

    /** Required trimmed text of min..max characters; never passes when NULL. */
    private static String text(String col, int min, int max) {
        return "coalesce(" + col + " IS NOT NULL AND typeof(" + col + ") = 'text' AND length(" + col + ") <= " + max
                + " AND length(trim(" + col + ", " + BLANKS + ")) >= " + min + ", 0)";
    }

    /** An INTEGER (never REAL / TEXT) satisfying cond; never passes when NULL. */
    private static String integer(String col, String cond) {
        return "coalesce(typeof(" + col + ") = 'integer' AND " + cond + ", 0)";
    }

    /** "this shift is CLOSED" for a shift-id expression. */
    private static final String CLOSED = "(SELECT status FROM cash_shifts WHERE shift_id = %s) = 'CLOSED'";

    private static String role(String userExpr) {
        return "(SELECT role FROM users WHERE user_id = " + userExpr + ")";
    }

    public static final List<String> SCHEMA = List.of(
            // ── stores: settings (D2, D4) ────────────────────────────────────────────────
            "ALTER TABLE stores ADD COLUMN require_cash_shift INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (require_cash_shift IN (0, 1))",
            "ALTER TABLE stores ADD COLUMN cash_rounding_minor INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (cash_rounding_minor IN (0, 5, 10, 50, 100))",
            // composite key for store-safe references to sales
            "CREATE UNIQUE INDEX ux_sales_id_store ON sales (sale_id, store_id)",

            // ── cash drawers and shifts (features 1, 2; D2, D3) ─────────────────────────
            """
            CREATE TABLE cash_drawers (
                drawer_id   INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id    INTEGER NOT NULL REFERENCES stores (store_id),
                name        TEXT    NOT NULL CHECK (%s),
                active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                created_at  TEXT    NOT NULL,
                UNIQUE (store_id, name COLLATE NOCASE),
                UNIQUE (drawer_id, store_id)
            )""".formatted(text("name", 1, 60)),
            """
            CREATE TABLE cash_shifts (
                shift_id             INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id             INTEGER NOT NULL,
                drawer_id            INTEGER NOT NULL,
                cashier_id           INTEGER NOT NULL,
                status               TEXT    NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
                opened_at            TEXT    NOT NULL,
                opening_cash_minor   INTEGER NOT NULL CHECK (%s),
                closed_at            TEXT,
                closed_by            INTEGER,
                close_kind           TEXT    CHECK (close_kind IN ('CASHIER', 'ADMIN_EXCEPTION')),
                close_reason         TEXT,
                expected_cash_minor  INTEGER,
                counted_cash_minor   INTEGER,
                difference_minor     INTEGER,
                closing_note         TEXT,
                version              INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (drawer_id, store_id)  REFERENCES cash_drawers (drawer_id, store_id),
                FOREIGN KEY (cashier_id, store_id) REFERENCES users (user_id, store_id),
                FOREIGN KEY (closed_by, store_id)  REFERENCES users (user_id, store_id),
                UNIQUE (shift_id, store_id),
                CHECK (coalesce(
                    (status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL AND close_kind IS NULL
                        AND close_reason IS NULL AND expected_cash_minor IS NULL AND counted_cash_minor IS NULL
                        AND difference_minor IS NULL)
                    OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL
                        AND typeof(expected_cash_minor) = 'integer' AND typeof(counted_cash_minor) = 'integer'
                        AND counted_cash_minor >= 0
                        AND difference_minor = counted_cash_minor - expected_cash_minor
                        AND (difference_minor = 0 OR %s)
                        AND (close_kind = 'CASHIER' OR (close_kind = 'ADMIN_EXCEPTION' AND %s))), 0))
            )""".formatted(integer("opening_cash_minor", "opening_cash_minor >= 0"),
                    text("closing_note", 3, 300), text("close_reason", 3, 300)),
            // one open shift per cashier, and one per physical drawer (shared drawer = hand-over)
            "CREATE UNIQUE INDEX ux_shift_open_cashier ON cash_shifts (store_id, cashier_id) WHERE status = 'OPEN'",
            "CREATE UNIQUE INDEX ux_shift_open_drawer ON cash_shifts (drawer_id) WHERE status = 'OPEN'",
            "CREATE INDEX ix_shifts_store_status ON cash_shifts (store_id, status)",
            """
            CREATE TRIGGER trg_shift_insert BEFORE INSERT ON cash_shifts
            WHEN coalesce(NEW.status IS NOT 'OPEN' OR NEW.version IS NOT 0
                OR (SELECT active FROM cash_drawers WHERE drawer_id = NEW.drawer_id) IS NOT 1
                OR %s NOT IN ('CASHIER', 'STORE_ADMIN'), 1)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_BAD_OPEN'); END""".formatted(role("NEW.cashier_id")),
            // a closed shift is frozen: no second closing, no edits (I6/I7)
            "CREATE TRIGGER trg_shift_closed_frozen BEFORE UPDATE ON cash_shifts WHEN OLD.status = 'CLOSED' "
                    + "BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSED'); END",
            """
            CREATE TRIGGER trg_shift_identity BEFORE UPDATE ON cash_shifts
            WHEN coalesce(NEW.store_id IS NOT OLD.store_id OR NEW.drawer_id IS NOT OLD.drawer_id
                OR NEW.cashier_id IS NOT OLD.cashier_id OR NEW.opened_at IS NOT OLD.opened_at
                OR NEW.opening_cash_minor IS NOT OLD.opening_cash_minor OR NEW.version IS NOT OLD.version + 1, 1)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_IDENTITY'); END""",
            // D3: the cashier closes own shift; a Store Admin may close any shift of the store with a reason
            """
            CREATE TRIGGER trg_shift_closer BEFORE UPDATE ON cash_shifts WHEN NEW.status = 'CLOSED'
            AND coalesce(NOT ((NEW.close_kind = 'CASHIER' AND NEW.closed_by = NEW.cashier_id)
                OR (NEW.close_kind = 'ADMIN_EXCEPTION' AND %s = 'STORE_ADMIN')), 1)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSER_NOT_ALLOWED'); END""".formatted(role("NEW.closed_by")),
            "CREATE TRIGGER trg_shift_no_delete BEFORE DELETE ON cash_shifts "
                    + "BEGIN SELECT RAISE(ABORT, 'SHIFT_NO_DELETE'); END",

            // ── product packages: piece / carton (feature 6; D7) ────────────────────────
            """
            CREATE TABLE product_packages (
                package_id         INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id           INTEGER NOT NULL,
                product_id         INTEGER NOT NULL,
                name               TEXT    NOT NULL CHECK (%s),
                units_per_package  INTEGER NOT NULL CHECK (%s),
                price_minor        INTEGER NOT NULL CHECK (%s),
                barcode            TEXT    CHECK (barcode IS NULL OR (length(barcode) BETWEEN 8 AND 14
                                                  AND barcode NOT GLOB '*[^0-9]*')),
                active             INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
                created_at         TEXT    NOT NULL,
                updated_at         TEXT    NOT NULL,
                version            INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (product_id, store_id) REFERENCES products (product_id, store_id) ON DELETE CASCADE,
                UNIQUE (store_id, barcode),
                UNIQUE (package_id, store_id)
            )""".formatted(text("name", 1, 60), integer("units_per_package", "units_per_package BETWEEN 2 AND 10000"),
                    integer("price_minor", "price_minor > 0")),
            "CREATE INDEX ix_packages_product ON product_packages (product_id)",
            // a package keeps its size for ever (history and reports); to change it: new package
            "CREATE TRIGGER trg_package_units_fixed BEFORE UPDATE OF units_per_package, product_id, store_id "
                    + "ON product_packages WHEN NEW.units_per_package IS NOT OLD.units_per_package "
                    + "OR NEW.product_id IS NOT OLD.product_id OR NEW.store_id IS NOT OLD.store_id "
                    + "BEGIN SELECT RAISE(ABORT, 'PACKAGE_IDENTITY_FIXED'); END",
            // no barcode shared between a product and a package of the same store
            """
            CREATE TRIGGER trg_package_barcode_insert BEFORE INSERT ON product_packages WHEN NEW.barcode IS NOT NULL
            AND EXISTS (SELECT 1 FROM products WHERE store_id = NEW.store_id AND barcode = NEW.barcode)
            BEGIN SELECT RAISE(ABORT, 'BARCODE_USED_BY_PRODUCT'); END""",
            """
            CREATE TRIGGER trg_package_barcode_update BEFORE UPDATE OF barcode ON product_packages
            WHEN NEW.barcode IS NOT NULL
            AND EXISTS (SELECT 1 FROM products WHERE store_id = NEW.store_id AND barcode = NEW.barcode)
            BEGIN SELECT RAISE(ABORT, 'BARCODE_USED_BY_PRODUCT'); END""",
            """
            CREATE TRIGGER trg_product_barcode_vs_package_insert BEFORE INSERT ON products
            WHEN EXISTS (SELECT 1 FROM product_packages WHERE store_id = NEW.store_id AND barcode = NEW.barcode)
            BEGIN SELECT RAISE(ABORT, 'BARCODE_USED_BY_PACKAGE'); END""",
            """
            CREATE TRIGGER trg_product_barcode_vs_package_update BEFORE UPDATE OF barcode ON products
            WHEN EXISTS (SELECT 1 FROM product_packages WHERE store_id = NEW.store_id AND barcode = NEW.barcode)
            BEGIN SELECT RAISE(ABORT, 'BARCODE_USED_BY_PACKAGE'); END""",

            // ── favorites (feature 5) ───────────────────────────────────────────────────
            """
            CREATE TABLE product_favorites (
                store_id    INTEGER NOT NULL,
                product_id  INTEGER NOT NULL,
                position    INTEGER NOT NULL CHECK (%s),
                created_by  INTEGER NOT NULL,
                created_at  TEXT    NOT NULL,
                PRIMARY KEY (store_id, product_id),
                FOREIGN KEY (product_id, store_id) REFERENCES products (product_id, store_id) ON DELETE CASCADE,
                FOREIGN KEY (created_by, store_id) REFERENCES users (user_id, store_id)
            )""".formatted(integer("position", "position >= 0")),
            // one product per position in a store: ordering is total and deterministic
            "CREATE UNIQUE INDEX ux_favorites_position ON product_favorites (store_id, position)",
            """
            CREATE TRIGGER trg_favorite_actor BEFORE INSERT ON product_favorites
            WHEN coalesce(%s IS NOT 'STORE_ADMIN', 1)
            BEGIN SELECT RAISE(ABORT, 'FAVORITE_ACTOR_NOT_STORE_ADMIN'); END""".formatted(role("NEW.created_by")),

            // ── sale lines: unit snapshot (feature 6) ───────────────────────────────────
            "ALTER TABLE sale_items ADD COLUMN package_id INTEGER REFERENCES product_packages (package_id) "
                    + "ON DELETE SET NULL",
            "ALTER TABLE sale_items ADD COLUMN package_name TEXT",
            "ALTER TABLE sale_items ADD COLUMN units_per_package INTEGER NOT NULL DEFAULT 1 "
                    + "CHECK (typeof(units_per_package) = 'integer' AND units_per_package >= 1)",
            "ALTER TABLE sale_items ADD COLUMN unit_price_minor INTEGER "
                    + "CHECK (unit_price_minor IS NULL OR (typeof(unit_price_minor) = 'integer' AND unit_price_minor > 0))",
            // a package line is a consistent snapshot: name and size recorded with it, same store
            """
            CREATE TRIGGER trg_sale_item_package BEFORE INSERT ON sale_items
            WHEN coalesce((NEW.package_id IS NULL AND (NEW.package_name IS NOT NULL OR NEW.units_per_package <> 1))
                OR (NEW.package_id IS NOT NULL AND (NEW.package_name IS NULL OR NEW.units_per_package
                    IS NOT (SELECT units_per_package FROM product_packages WHERE package_id = NEW.package_id)
                    OR (SELECT store_id FROM product_packages WHERE package_id = NEW.package_id)
                       IS NOT (SELECT store_id FROM sales WHERE sale_id = NEW.sale_id))), 1)
            BEGIN SELECT RAISE(ABORT, 'SALE_ITEM_PACKAGE_SNAPSHOT'); END""",
            """
            CREATE TRIGGER trg_sale_item_price_minor BEFORE INSERT ON sale_items
            WHEN NEW.unit_price_minor IS NOT NULL
            AND NEW.unit_price_minor IS NOT CAST(round(NEW.unit_price * 100) AS INTEGER)
            BEGIN SELECT RAISE(ABORT, 'SALE_ITEM_PRICE_MISMATCH'); END""",
            """
            CREATE TRIGGER trg_sale_item_snapshot_fixed BEFORE UPDATE OF units_per_package, package_name ON sale_items
            WHEN NEW.units_per_package IS NOT OLD.units_per_package OR NEW.package_name IS NOT OLD.package_name
            BEGIN SELECT RAISE(ABORT, 'SALE_ITEM_SNAPSHOT_FIXED'); END""",

            // ── sales: shift, cash rounding, idempotency (features 1, 7; I3, I7) ────────
            "ALTER TABLE sales ADD COLUMN shift_id INTEGER REFERENCES cash_shifts (shift_id)",
            "ALTER TABLE sales ADD COLUMN rounding_minor INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (typeof(rounding_minor) = 'integer' AND rounding_minor BETWEEN -50 AND 50)",
            "ALTER TABLE sales ADD COLUMN client_request_id TEXT "
                    + "CHECK (client_request_id IS NULL OR length(client_request_id) BETWEEN 8 AND 100)",
            "CREATE UNIQUE INDEX ux_sales_client_request ON sales (store_id, client_request_id) "
                    + "WHERE client_request_id IS NOT NULL",
            "CREATE INDEX ix_sales_shift ON sales (shift_id)",
            """
            CREATE TRIGGER trg_sale_shift_insert BEFORE INSERT ON sales WHEN NEW.shift_id IS NOT NULL
            AND coalesce((SELECT status FROM cash_shifts WHERE shift_id = NEW.shift_id) IS NOT 'OPEN'
                OR (SELECT store_id FROM cash_shifts WHERE shift_id = NEW.shift_id) IS NOT NEW.store_id, 1)
            BEGIN SELECT RAISE(ABORT, 'SALE_SHIFT_NOT_OPEN'); END""",
            """
            CREATE TRIGGER trg_sale_shift_update BEFORE UPDATE OF shift_id ON sales
            WHEN NEW.shift_id IS NOT OLD.shift_id AND coalesce(OLD.shift_id IS NOT NULL
                OR (SELECT status FROM cash_shifts WHERE shift_id = NEW.shift_id) IS NOT 'OPEN'
                OR (SELECT store_id FROM cash_shifts WHERE shift_id = NEW.shift_id) IS NOT NEW.store_id, 1)
            BEGIN SELECT RAISE(ABORT, 'SALE_SHIFT_NOT_OPEN'); END""",
            """
            CREATE TRIGGER trg_sale_shift_required_insert BEFORE INSERT ON sales
            WHEN NEW.status = 'COMPLETED' AND NEW.shift_id IS NULL
            AND coalesce((SELECT require_cash_shift FROM stores WHERE store_id = NEW.store_id) = 1, 0)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_REQUIRED'); END""",
            """
            CREATE TRIGGER trg_sale_shift_required_update BEFORE UPDATE OF status ON sales
            WHEN NEW.status = 'COMPLETED' AND OLD.status IS NOT 'COMPLETED' AND NEW.shift_id IS NULL
            AND coalesce((SELECT require_cash_shift FROM stores WHERE store_id = NEW.store_id) = 1, 0)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_REQUIRED'); END""",
            // the rounding of a completed sale never changes afterwards
            """
            CREATE TRIGGER trg_sale_rounding_fixed BEFORE UPDATE OF rounding_minor ON sales
            WHEN OLD.status = 'COMPLETED' AND NEW.rounding_minor IS NOT OLD.rounding_minor
            BEGIN SELECT RAISE(ABORT, 'SALE_ROUNDING_FIXED'); END""",

            // ── payments: rebuilt for CHEQUE, tendered / change, one payment per sale ───
            """
            CREATE TABLE payments_v7 (
                payment_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                sale_id         INTEGER NOT NULL UNIQUE REFERENCES sales (sale_id) ON DELETE CASCADE,
                payment_method  TEXT    NOT NULL CHECK (payment_method IN ('CASH', 'CARD', 'WALLET', 'BANK_TRANSFER', 'CHEQUE')),
                amount          REAL    NOT NULL,
                payment_status  TEXT    NOT NULL CHECK (payment_status IN ('PAID')),
                paid_at         TEXT    NOT NULL,
                tendered_minor  INTEGER,
                change_minor    INTEGER,
                CHECK (coalesce((tendered_minor IS NULL AND change_minor IS NULL)
                    OR (payment_method = 'CASH' AND typeof(tendered_minor) = 'integer'
                        AND typeof(change_minor) = 'integer' AND change_minor >= 0
                        AND tendered_minor - change_minor = CAST(round(amount * 100) AS INTEGER)), 0))
            )""",
            "INSERT INTO payments_v7 (payment_id, sale_id, payment_method, amount, payment_status, paid_at) "
                    + "SELECT payment_id, sale_id, payment_method, amount, payment_status, paid_at FROM payments "
                    + "ORDER BY payment_id",
            "DROP TABLE payments",
            "ALTER TABLE payments_v7 RENAME TO payments",
            "CREATE INDEX idx_payments_sale ON payments (sale_id)",
            // new payments: amount = invoice total + cash rounding; rounding only for CASH (D4)
            """
            CREATE TRIGGER trg_payment_amount BEFORE INSERT ON payments
            WHEN coalesce(CAST(round(NEW.amount * 100) AS INTEGER) IS NOT
                    (SELECT CAST(round(total * 100) AS INTEGER) + rounding_minor FROM sales WHERE sale_id = NEW.sale_id)
                OR ((SELECT rounding_minor FROM sales WHERE sale_id = NEW.sale_id) <> 0
                    AND NEW.payment_method <> 'CASH'), 1)
            BEGIN SELECT RAISE(ABORT, 'PAYMENT_AMOUNT_MISMATCH'); END""",
            "CREATE TRIGGER trg_payment_no_update BEFORE UPDATE ON payments "
                    + "BEGIN SELECT RAISE(ABORT, 'PAYMENT_NO_UPDATE'); END",

            // ── returns: shift, exact refund, refund rounding (features 1, 7) ───────────
            "ALTER TABLE returns ADD COLUMN shift_id INTEGER REFERENCES cash_shifts (shift_id)",
            "ALTER TABLE returns ADD COLUMN refund_minor INTEGER "
                    + "CHECK (refund_minor IS NULL OR (typeof(refund_minor) = 'integer' AND refund_minor >= 0))",
            "ALTER TABLE returns ADD COLUMN rounding_minor INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (typeof(rounding_minor) = 'integer' AND rounding_minor BETWEEN -50 AND 50)",
            "CREATE INDEX ix_returns_shift ON returns (shift_id)",
            // composite key: a cheque refund can only name a return together with ITS sale and store
            "CREATE UNIQUE INDEX ux_returns_id_sale_store ON returns (return_id, sale_id, store_id)",
            // a refund out of a drawer: an OPEN shift of this store, the returning user's own shift
            // (or a Store Admin acting on a cashier's drawer)
            """
            CREATE TRIGGER trg_return_shift BEFORE INSERT ON returns WHEN NEW.shift_id IS NOT NULL
            AND coalesce((SELECT status FROM cash_shifts WHERE shift_id = NEW.shift_id) IS NOT 'OPEN'
                OR (SELECT store_id FROM cash_shifts WHERE shift_id = NEW.shift_id) IS NOT NEW.store_id
                OR ((SELECT cashier_id FROM cash_shifts WHERE shift_id = NEW.shift_id) IS NOT NEW.cashier_id
                    AND %s IS NOT 'STORE_ADMIN'), 1)
            BEGIN SELECT RAISE(ABORT, 'RETURN_SHIFT_NOT_OPEN'); END""".formatted(role("NEW.cashier_id")),
            """
            CREATE TRIGGER trg_return_cash_shift_required BEFORE INSERT ON returns
            WHEN NEW.refund_method = 'CASH' AND NEW.shift_id IS NULL
            AND coalesce((SELECT require_cash_shift FROM stores WHERE store_id = NEW.store_id) = 1, 0)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_REQUIRED'); END""",
            // amounts of a new return: refund method = the sale's own payment method (as the code does);
            // exact minor amount = the REAL amount; rounding only on a cash refund; never more refunded
            // in total than the customer paid for the invoice
            """
            CREATE TRIGGER trg_return_amounts BEFORE INSERT ON returns
            WHEN coalesce(NEW.refund_method IS NOT (SELECT payment_method FROM payments WHERE sale_id = NEW.sale_id)
                OR (NEW.refund_minor IS NOT NULL
                    AND NEW.refund_minor IS NOT CAST(round(NEW.refund_amount * 100) AS INTEGER))
                OR (NEW.rounding_minor <> 0 AND (NEW.refund_method IS NOT 'CASH' OR NEW.refund_minor IS NULL))
                OR (NEW.refund_minor IS NOT NULL AND NEW.refund_minor
                    + coalesce((SELECT sum(coalesce(r.refund_minor, CAST(round(r.refund_amount * 100) AS INTEGER)))
                                FROM returns r WHERE r.sale_id = NEW.sale_id), 0)
                    > (SELECT CAST(round(amount * 100) AS INTEGER) FROM payments WHERE sale_id = NEW.sale_id)), 1)
            BEGIN SELECT RAISE(ABORT, 'RETURN_AMOUNT_INVALID'); END""",
            // D6: a return of a cheque sale is made by a Store Admin, with an exact amount (no cash now)
            """
            CREATE TRIGGER trg_return_cheque_sale BEFORE INSERT ON returns
            WHEN (SELECT payment_method FROM payments WHERE sale_id = NEW.sale_id) = 'CHEQUE'
            AND coalesce(%s IS NOT 'STORE_ADMIN' OR NEW.refund_minor IS NULL, 1)
            BEGIN SELECT RAISE(ABORT, 'CHEQUE_RETURN_NEEDS_STORE_ADMIN'); END""".formatted(role("NEW.cashier_id")),
            "CREATE TRIGGER trg_return_no_update BEFORE UPDATE OF quantity, refund_amount, refund_minor, rounding_minor, "
                    + "refund_method, shift_id, sale_id, store_id ON returns BEGIN SELECT RAISE(ABORT, 'RETURN_FIXED'); END",

            // ── cheques (feature 9; D6) ─────────────────────────────────────────────────
            """
            CREATE TABLE cheques (
                cheque_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id       INTEGER NOT NULL,
                sale_id        INTEGER NOT NULL UNIQUE,
                payment_id     INTEGER NOT NULL UNIQUE REFERENCES payments (payment_id),
                cheque_number  TEXT    NOT NULL CHECK (%s),
                bank_name      TEXT    NOT NULL CHECK (%s),
                amount_minor   INTEGER NOT NULL CHECK (%s),
                due_date       TEXT    NOT NULL CHECK (coalesce(date(due_date) = due_date, 0)),
                notes          TEXT    CHECK (notes IS NULL OR length(notes) <= 300),
                status         TEXT    NOT NULL CHECK (status IN ('RECEIVED', 'DEPOSITED', 'CLEARED', 'BOUNCED', 'CANCELLED')),
                created_by     INTEGER NOT NULL,
                created_at     TEXT    NOT NULL,
                updated_at     TEXT    NOT NULL,
                version        INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (sale_id, store_id)    REFERENCES sales (sale_id, store_id),
                FOREIGN KEY (created_by, store_id) REFERENCES users (user_id, store_id),
                UNIQUE (store_id, bank_name COLLATE NOCASE, cheque_number),
                UNIQUE (cheque_id, store_id),
                UNIQUE (cheque_id, sale_id, store_id)
            )""".formatted(text("cheque_number", 1, 30), text("bank_name", 2, 80),
                    integer("amount_minor", "amount_minor > 0")),
            "CREATE INDEX ix_cheques_store_status_due ON cheques (store_id, status, due_date)",
            // recorded RECEIVED, for the CHEQUE payment of that COMPLETED sale, for its full amount
            """
            CREATE TRIGGER trg_cheque_insert BEFORE INSERT ON cheques
            WHEN coalesce(NEW.status IS NOT 'RECEIVED' OR NEW.version IS NOT 0
                OR (SELECT status FROM sales WHERE sale_id = NEW.sale_id) IS NOT 'COMPLETED'
                OR (SELECT payment_method FROM payments WHERE payment_id = NEW.payment_id) IS NOT 'CHEQUE'
                OR (SELECT sale_id FROM payments WHERE payment_id = NEW.payment_id) IS NOT NEW.sale_id
                OR (SELECT CAST(round(amount * 100) AS INTEGER) FROM payments WHERE payment_id = NEW.payment_id)
                    IS NOT NEW.amount_minor, 1)
            BEGIN SELECT RAISE(ABORT, 'CHEQUE_BAD_INSERT'); END""",
            """
            CREATE TABLE cheque_events (
                event_id     INTEGER PRIMARY KEY AUTOINCREMENT,
                cheque_id    INTEGER NOT NULL,
                store_id     INTEGER NOT NULL,
                from_status  TEXT    CHECK (from_status IS NULL
                                   OR from_status IN ('RECEIVED', 'DEPOSITED', 'CLEARED', 'BOUNCED', 'CANCELLED')),
                to_status    TEXT    NOT NULL CHECK (to_status IN ('RECEIVED', 'DEPOSITED', 'CLEARED', 'BOUNCED', 'CANCELLED')),
                to_version   INTEGER NOT NULL CHECK (%s),
                user_id      INTEGER NOT NULL,
                created_at   TEXT    NOT NULL CHECK (length(created_at) >= 10),
                note         TEXT    CHECK (note IS NULL OR length(note) <= 300),
                FOREIGN KEY (cheque_id, store_id) REFERENCES cheques (cheque_id, store_id),
                FOREIGN KEY (user_id, store_id)   REFERENCES users (user_id, store_id),
                UNIQUE (cheque_id, to_version),
                CHECK (to_status NOT IN ('BOUNCED', 'CANCELLED') OR %s)
            )""".formatted(integer("to_version", "to_version >= 0"), text("note", 3, 300)),
            // the first event (RECEIVED, version 0) is written by the database with the cheque itself
            """
            CREATE TRIGGER trg_cheque_initial_event AFTER INSERT ON cheques
            BEGIN
                INSERT INTO cheque_events (cheque_id, store_id, from_status, to_status, to_version, user_id, created_at)
                VALUES (NEW.cheque_id, NEW.store_id, NULL, 'RECEIVED', 0, NEW.created_by, NEW.created_at);
            END""",
            // an event must describe exactly the next transition of the cheque, by a Store Admin
            """
            CREATE TRIGGER trg_cheque_event_valid BEFORE INSERT ON cheque_events
            WHEN coalesce(
                (NEW.from_status IS NULL AND NOT (NEW.to_status = 'RECEIVED' AND NEW.to_version = 0
                    AND (SELECT version FROM cheques WHERE cheque_id = NEW.cheque_id) = 0
                    AND (SELECT status FROM cheques WHERE cheque_id = NEW.cheque_id) = 'RECEIVED'
                    AND (SELECT created_by FROM cheques WHERE cheque_id = NEW.cheque_id) = NEW.user_id
                    AND (SELECT created_at FROM cheques WHERE cheque_id = NEW.cheque_id) = NEW.created_at))
                OR (NEW.from_status IS NOT NULL AND NOT (
                    NEW.from_status = (SELECT status FROM cheques WHERE cheque_id = NEW.cheque_id)
                    AND NEW.to_version = (SELECT version FROM cheques WHERE cheque_id = NEW.cheque_id) + 1
                    AND ((NEW.from_status = 'RECEIVED' AND NEW.to_status IN ('DEPOSITED', 'CANCELLED'))
                         OR (NEW.from_status = 'DEPOSITED' AND NEW.to_status IN ('CLEARED', 'BOUNCED'))
                         OR (NEW.from_status = 'BOUNCED' AND NEW.to_status IN ('DEPOSITED', 'CANCELLED')))
                    AND %s = 'STORE_ADMIN')), 1)
            BEGIN SELECT RAISE(ABORT, 'CHEQUE_EVENT_INVALID'); END""".formatted(role("NEW.user_id")),
            // the event APPLIES the transition (same statement, same transaction): no event, no transition
            """
            CREATE TRIGGER trg_cheque_event_apply AFTER INSERT ON cheque_events WHEN NEW.from_status IS NOT NULL
            BEGIN
                UPDATE cheques SET status = NEW.to_status, version = NEW.to_version, updated_at = NEW.created_at
                WHERE cheque_id = NEW.cheque_id;
            END""",
            // any other change of a cheque is refused: every committed transition has its event
            """
            CREATE TRIGGER trg_cheque_update_needs_event BEFORE UPDATE ON cheques
            WHEN NOT EXISTS (SELECT 1 FROM cheque_events e WHERE e.cheque_id = OLD.cheque_id
                AND e.to_version = NEW.version AND e.from_status IS OLD.status AND e.to_status IS NEW.status
                AND e.created_at IS NEW.updated_at)
            BEGIN SELECT RAISE(ABORT, 'CHEQUE_UPDATE_WITHOUT_EVENT'); END""",
            """
            CREATE TRIGGER trg_cheque_identity BEFORE UPDATE ON cheques
            WHEN coalesce(NEW.store_id IS NOT OLD.store_id OR NEW.sale_id IS NOT OLD.sale_id
                OR NEW.payment_id IS NOT OLD.payment_id OR NEW.cheque_number IS NOT OLD.cheque_number
                OR NEW.bank_name IS NOT OLD.bank_name OR NEW.amount_minor IS NOT OLD.amount_minor
                OR NEW.due_date IS NOT OLD.due_date OR NEW.notes IS NOT OLD.notes
                OR NEW.created_by IS NOT OLD.created_by OR NEW.created_at IS NOT OLD.created_at
                OR NEW.version IS NOT OLD.version + 1, 1)
            BEGIN SELECT RAISE(ABORT, 'CHEQUE_IDENTITY'); END""",
            "CREATE TRIGGER trg_cheque_no_delete BEFORE DELETE ON cheques "
                    + "BEGIN SELECT RAISE(ABORT, 'CHEQUE_NO_DELETE'); END",
            "CREATE TRIGGER trg_cheque_events_no_update BEFORE UPDATE ON cheque_events "
                    + "BEGIN SELECT RAISE(ABORT, 'CHEQUE_EVENTS_APPEND_ONLY'); END",
            "CREATE TRIGGER trg_cheque_events_no_delete BEFORE DELETE ON cheque_events "
                    + "BEGIN SELECT RAISE(ABORT, 'CHEQUE_EVENTS_APPEND_ONLY'); END",

            // D6: what is owed back for a return of a cheque sale; same sale, same store, by construction
            """
            CREATE TABLE cheque_refunds (
                cheque_refund_id    INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id            INTEGER NOT NULL,
                sale_id             INTEGER NOT NULL,
                return_id           INTEGER NOT NULL UNIQUE,
                cheque_id           INTEGER NOT NULL,
                amount_minor        INTEGER NOT NULL CHECK (%s),
                status              TEXT    NOT NULL CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
                paid_method         TEXT    CHECK (paid_method IN ('CASH', 'BANK_TRANSFER')),
                payout_movement_id  INTEGER UNIQUE,
                decided_by          INTEGER,
                decided_at          TEXT,
                reason              TEXT,
                created_at          TEXT    NOT NULL,
                version             INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (return_id, sale_id, store_id) REFERENCES returns (return_id, sale_id, store_id),
                FOREIGN KEY (cheque_id, sale_id, store_id) REFERENCES cheques (cheque_id, sale_id, store_id),
                FOREIGN KEY (decided_by, store_id)         REFERENCES users (user_id, store_id),
                -- a cash payout points to ITS movement, which points back (checked at COMMIT)
                FOREIGN KEY (payout_movement_id, cheque_refund_id)
                    REFERENCES cash_movements (movement_id, cheque_refund_id) DEFERRABLE INITIALLY DEFERRED,
                UNIQUE (cheque_refund_id, store_id),
                UNIQUE (cheque_refund_id, payout_movement_id),
                CHECK (coalesce(
                    (status = 'PENDING' AND paid_method IS NULL AND decided_by IS NULL AND decided_at IS NULL)
                    OR (status = 'PAID' AND paid_method IS NOT NULL AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
                    OR (status = 'CANCELLED' AND paid_method IS NULL AND decided_by IS NOT NULL
                        AND decided_at IS NOT NULL AND %s), 0)),
                CHECK (coalesce(status = 'PAID' AND paid_method = 'CASH', 0) = (payout_movement_id IS NOT NULL))
            )""".formatted(integer("amount_minor", "amount_minor > 0"), text("reason", 3, 300)),
            // starts PENDING, for exactly the return's refund, of a CHEQUE return, within the cheque amount
            """
            CREATE TRIGGER trg_cheque_refund_insert BEFORE INSERT ON cheque_refunds
            WHEN coalesce(NEW.status IS NOT 'PENDING' OR NEW.version IS NOT 0 OR NEW.payout_movement_id IS NOT NULL
                OR (SELECT refund_method FROM returns WHERE return_id = NEW.return_id) IS NOT 'CHEQUE'
                OR (SELECT refund_minor FROM returns WHERE return_id = NEW.return_id) IS NOT NEW.amount_minor
                OR NEW.amount_minor + coalesce((SELECT sum(amount_minor) FROM cheque_refunds
                       WHERE cheque_id = NEW.cheque_id AND status <> 'CANCELLED'), 0)
                   > (SELECT amount_minor FROM cheques WHERE cheque_id = NEW.cheque_id), 1)
            BEGIN SELECT RAISE(ABORT, 'CHEQUE_REFUND_BAD_INSERT'); END""",
            // decided once, by a Store Admin; PAID only once the cheque is CLEARED; a CASH payout needs
            // (null-safe IS: a CANCELLED decision has no paid_method, and NULL must not block it)
            // its movement (written first, same transaction); a bank transfer has no drawer movement
            """
            CREATE TRIGGER trg_cheque_refund_decide BEFORE UPDATE ON cheque_refunds
            WHEN coalesce(OLD.status IS NOT 'PENDING' OR NEW.status NOT IN ('PAID', 'CANCELLED')
                OR NEW.amount_minor IS NOT OLD.amount_minor OR NEW.cheque_id IS NOT OLD.cheque_id
                OR NEW.return_id IS NOT OLD.return_id OR NEW.sale_id IS NOT OLD.sale_id
                OR NEW.store_id IS NOT OLD.store_id OR NEW.version IS NOT OLD.version + 1
                OR %s IS NOT 'STORE_ADMIN'
                OR (NEW.status = 'PAID' AND (SELECT status FROM cheques WHERE cheque_id = NEW.cheque_id) IS NOT 'CLEARED')
                OR (NEW.paid_method IS 'CASH' AND NOT EXISTS (SELECT 1 FROM cash_movements m
                    WHERE m.movement_id = NEW.payout_movement_id AND m.cheque_refund_id = NEW.cheque_refund_id
                      AND m.amount_minor = NEW.amount_minor AND m.created_by = NEW.decided_by))
                OR (NEW.paid_method IS NOT 'CASH' AND EXISTS (SELECT 1 FROM cash_movements m
                    WHERE m.cheque_refund_id = NEW.cheque_refund_id)), 1)
            BEGIN SELECT RAISE(ABORT, 'CHEQUE_REFUND_BAD_DECISION'); END""".formatted(role("NEW.decided_by")),
            "CREATE TRIGGER trg_cheque_refund_no_delete BEFORE DELETE ON cheque_refunds "
                    + "BEGIN SELECT RAISE(ABORT, 'CHEQUE_REFUND_NO_DELETE'); END",

            // ── manual cash movements (features 1, 2; D3) ───────────────────────────────
            """
            CREATE TABLE cash_movements (
                movement_id       INTEGER PRIMARY KEY AUTOINCREMENT,
                store_id          INTEGER NOT NULL,
                shift_id          INTEGER NOT NULL,
                type              TEXT    NOT NULL CHECK (type IN ('CASH_IN', 'CASH_OUT', 'CHEQUE_REFUND_OUT')),
                amount_minor      INTEGER NOT NULL CHECK (%s),
                reason            TEXT    NOT NULL CHECK (%s),
                cheque_refund_id  INTEGER UNIQUE,
                created_by        INTEGER NOT NULL,
                created_at        TEXT    NOT NULL,
                request_key       TEXT    NOT NULL UNIQUE CHECK (length(request_key) BETWEEN 8 AND 100),
                FOREIGN KEY (shift_id, store_id)         REFERENCES cash_shifts (shift_id, store_id),
                FOREIGN KEY (created_by, store_id)       REFERENCES users (user_id, store_id),
                FOREIGN KEY (cheque_refund_id, store_id) REFERENCES cheque_refunds (cheque_refund_id, store_id),
                -- the refund paid by this movement must be PAID with THIS movement (checked at COMMIT)
                FOREIGN KEY (cheque_refund_id, movement_id)
                    REFERENCES cheque_refunds (cheque_refund_id, payout_movement_id) DEFERRABLE INITIALLY DEFERRED,
                UNIQUE (movement_id, cheque_refund_id),
                CHECK ((type = 'CHEQUE_REFUND_OUT') = (cheque_refund_id IS NOT NULL))
            )""".formatted(integer("amount_minor", "amount_minor > 0"), text("reason", 3, 300)),
            "CREATE INDEX ix_cash_movements_shift ON cash_movements (shift_id)",
            """
            CREATE TRIGGER trg_cash_movement_insert BEFORE INSERT ON cash_movements
            WHEN coalesce((SELECT status FROM cash_shifts WHERE shift_id = NEW.shift_id) IS NOT 'OPEN'
                OR %s IS NOT 'STORE_ADMIN'
                OR (NEW.cheque_refund_id IS NOT NULL AND (
                    (SELECT status FROM cheque_refunds WHERE cheque_refund_id = NEW.cheque_refund_id) IS NOT 'PENDING'
                    OR (SELECT amount_minor FROM cheque_refunds WHERE cheque_refund_id = NEW.cheque_refund_id)
                        IS NOT NEW.amount_minor)), 1)
            BEGIN SELECT RAISE(ABORT, 'CASH_MOVEMENT_NOT_ALLOWED'); END""".formatted(role("NEW.created_by")),
            "CREATE TRIGGER trg_cash_movement_no_update BEFORE UPDATE ON cash_movements "
                    + "BEGIN SELECT RAISE(ABORT, 'CASH_MOVEMENTS_APPEND_ONLY'); END",
            "CREATE TRIGGER trg_cash_movement_no_delete BEFORE DELETE ON cash_movements "
                    + "BEGIN SELECT RAISE(ABORT, 'CASH_MOVEMENTS_APPEND_ONLY'); END",

            // ── a closed shift's financial history is frozen (problems A–D) ─────────────
            // only a COMPLETED sale carries a shift (a suspended sale belongs to none)
            """
            CREATE TRIGGER trg_sale_shift_completed_only_insert BEFORE INSERT ON sales
            WHEN NEW.shift_id IS NOT NULL AND NEW.status IS NOT 'COMPLETED'
            BEGIN SELECT RAISE(ABORT, 'SALE_SHIFT_NEEDS_COMPLETED'); END""",
            """
            CREATE TRIGGER trg_sale_shift_completed_only_update BEFORE UPDATE ON sales
            WHEN NEW.shift_id IS NOT NULL AND NEW.status IS NOT 'COMPLETED'
            BEGIN SELECT RAISE(ABORT, 'SALE_SHIFT_NEEDS_COMPLETED'); END""",
            // A: no financial change to a sale of a closed shift (status, amounts, shift, date)
            """
            CREATE TRIGGER trg_sale_closed_shift_frozen BEFORE UPDATE OF status, subtotal, discount, tax_rate, tax, total,
                rounding_minor, shift_id, completed_at, store_id ON sales
            WHEN coalesce(%s OR %s, 0)
            AND (NEW.status IS NOT OLD.status OR NEW.subtotal IS NOT OLD.subtotal OR NEW.discount IS NOT OLD.discount
                OR NEW.tax_rate IS NOT OLD.tax_rate OR NEW.tax IS NOT OLD.tax OR NEW.total IS NOT OLD.total
                OR NEW.rounding_minor IS NOT OLD.rounding_minor OR NEW.shift_id IS NOT OLD.shift_id
                OR NEW.completed_at IS NOT OLD.completed_at OR NEW.store_id IS NOT OLD.store_id)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSED_RECORD'); END""".formatted(CLOSED.formatted("OLD.shift_id"), CLOSED.formatted("NEW.shift_id")),
            """
            CREATE TRIGGER trg_sale_closed_shift_no_delete BEFORE DELETE ON sales
            WHEN coalesce(%s, 0)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSED_RECORD'); END""".formatted(CLOSED.formatted("OLD.shift_id")),
            // B, C: no new payment, and no deletion of a payment, of a sale of a closed shift
            """
            CREATE TRIGGER trg_payment_closed_shift_insert BEFORE INSERT ON payments
            WHEN coalesce(%s, 0)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSED_RECORD'); END""".formatted(CLOSED.formatted("(SELECT shift_id FROM sales WHERE sale_id = NEW.sale_id)")),
            """
            CREATE TRIGGER trg_payment_closed_shift_delete BEFORE DELETE ON payments
            WHEN coalesce(%s, 0)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSED_RECORD'); END""".formatted(CLOSED.formatted("(SELECT shift_id FROM sales WHERE sale_id = OLD.sale_id)")),
            // the lines of a closed-shift sale keep their amounts (returned_quantity may still grow)
            """
            CREATE TRIGGER trg_sale_item_closed_shift_update BEFORE UPDATE OF sale_id, quantity, unit_price, line_total,
                unit_price_minor ON sale_items
            WHEN coalesce(%s, 0)
            AND (NEW.sale_id IS NOT OLD.sale_id OR NEW.quantity IS NOT OLD.quantity OR NEW.unit_price IS NOT OLD.unit_price
                OR NEW.line_total IS NOT OLD.line_total OR NEW.unit_price_minor IS NOT OLD.unit_price_minor)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSED_RECORD'); END""".formatted(CLOSED.formatted("(SELECT shift_id FROM sales WHERE sale_id = OLD.sale_id)")),
            """
            CREATE TRIGGER trg_sale_item_closed_shift_delete BEFORE DELETE ON sale_items
            WHEN coalesce(%s, 0)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSED_RECORD'); END""".formatted(CLOSED.formatted("(SELECT shift_id FROM sales WHERE sale_id = OLD.sale_id)")),
            // D: a cash return of a closed shift is never deleted (its amounts are already fixed)
            """
            CREATE TRIGGER trg_return_closed_shift_no_delete BEFORE DELETE ON returns
            WHEN coalesce(%s, 0)
            BEGIN SELECT RAISE(ABORT, 'SHIFT_CLOSED_RECORD'); END""".formatted(CLOSED.formatted("OLD.shift_id")),

            // ── shift closing: the expected cash is the database's own calculation (I3) ─
            """
            CREATE TRIGGER trg_shift_expected BEFORE UPDATE ON cash_shifts
            WHEN NEW.status = 'CLOSED' AND OLD.status = 'OPEN' AND NEW.expected_cash_minor IS NOT (
                OLD.opening_cash_minor
                + coalesce((SELECT sum(CAST(round(p.amount * 100) AS INTEGER)) FROM payments p
                            JOIN sales s ON s.sale_id = p.sale_id
                            WHERE s.shift_id = OLD.shift_id AND p.payment_method = 'CASH'), 0)
                - coalesce((SELECT sum(coalesce(r.refund_minor, CAST(round(r.refund_amount * 100) AS INTEGER)))
                            FROM returns r WHERE r.shift_id = OLD.shift_id AND r.refund_method = 'CASH'), 0)
                + coalesce((SELECT sum(amount_minor) FROM cash_movements
                            WHERE shift_id = OLD.shift_id AND type = 'CASH_IN'), 0)
                - coalesce((SELECT sum(amount_minor) FROM cash_movements
                            WHERE shift_id = OLD.shift_id AND type IN ('CASH_OUT', 'CHEQUE_REFUND_OUT')), 0))
            BEGIN SELECT RAISE(ABORT, 'SHIFT_EXPECTED_MISMATCH'); END"""
    );

    // ── the migration procedure ─────────────────────────────────────────────────────

    /** Result of a run: the verified backup file (null for an empty database). */
    public record Outcome(Path backup) {
    }

    /** Thrown when the backup step fails: the database was not changed. */
    public static final class BackupFailed extends IllegalStateException {
        BackupFailed(String m, Throwable cause) {
            super(m, cause);
        }
    }

    /** VACUUM INTO (the backup) cannot run inside a transaction, so this migration manages its own. */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    /** Flyway entry point: the database file is the connection's "main" database. */
    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        String file = one(c, "SELECT file FROM pragma_database_list WHERE name = 'main'");
        Path dbFile = file == null || file.isBlank() ? null : Path.of(file);
        Outcome outcome = apply(c, dbFile, null, null);
        if (outcome.backup() != null) log.info("Database backup written and checked: {}", outcome.backup());
        log.info("V7 store-operations schema added; existing rows kept unchanged");
    }

    /**
     * Runs pre-flight, backup and the migration transaction on {@code dbFile} with its own connection
     * (used by the tests on disposable databases).
     *
     * @param afterStatement test hook called after each schema statement (index) inside the
     *                       transaction; throwing from it simulates a failure mid-migration
     * @param backupName     test hook for the backup file name; null = default safe name
     */
    public static Outcome migrate(Path dbFile, IntConsumer afterStatement, String backupName) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile + "?foreign_keys=on")) {
            return apply(c, dbFile, afterStatement, backupName);
        }
    }

    /** The procedure itself, on an open connection in auto-commit mode. */
    static Outcome apply(Connection c, Path dbFile, IntConsumer afterStatement, String backupName) throws Exception {
        preflight(c);
        List<String> tables = tables(c);
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (String t : tables) columns.put(t, columns(c, t));
        Map<String, Long> rows = counts(c, tables);
        Map<String, String> hashes = hashes(c, columns);
        TreeSet<String> objects = objects(c);
        Long paymentSeq = paymentSequence(c);

        Path backup = null;
        if (rows.values().stream().anyMatch(n -> n > 0)) {
            if (dbFile == null) {
                throw new BackupFailed("The database has data but no file to back up next to; nothing was changed", null);
            }
            backup = dbFile.toAbsolutePath().getParent().resolve(backupName != null ? backupName : backupName());
            try {
                writeBackup(c, backup, rows, hashes, columns);
            } catch (Exception e) {
                throw new BackupFailed("Backup failed; the database was not changed: " + e.getMessage(), e);
            }
        }

        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            for (int i = 0; i < SCHEMA.size(); i++) {
                exec(c, SCHEMA.get(i));
                if (afterStatement != null) afterStatement.accept(i);
            }
            // keep the AUTOINCREMENT high-water mark of the rebuilt payments table
            if (paymentSeq != null) {
                exec(c, "UPDATE sqlite_sequence SET seq = max(seq, " + paymentSeq + ") WHERE name = 'payments'");
                if (count(c, "SELECT COUNT(*) FROM sqlite_sequence WHERE name = 'payments'") == 0) {
                    exec(c, "INSERT INTO sqlite_sequence (name, seq) VALUES ('payments', " + paymentSeq + ")");
                }
            }
            verifyAfter(c, rows, hashes, columns, objects);
            c.commit();
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(autoCommit);
        }
        return new Outcome(backup);
    }

    /** Next to the database, with time and 8 random hex characters: collisions are practically impossible. */
    static String backupName() {
        byte[] r = new byte[4];
        new SecureRandom().nextBytes(r);
        return BACKUP_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                + "-" + HexFormat.of().formatHex(r) + ".db";
    }

    /** Stops before ANY change (no backup, no schema) when the database is not a sound V6 database. */
    static void preflight(Connection c) throws SQLException {
        String integrity = one(c, "PRAGMA integrity_check");
        if (!"ok".equalsIgnoreCase(integrity)) {
            throw new IllegalStateException("Integrity check failed (" + integrity + "); nothing was changed");
        }
        if (count(c, "SELECT COUNT(*) FROM pragma_foreign_key_check") > 0) {
            throw new IllegalStateException("Broken references in the database; nothing was changed");
        }
        if (count(c, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'flyway_schema_history'") > 0) {
            String v = one(c, "SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success = 1");
            if (!"6".equals(v)) {
                throw new IllegalStateException("Database schema is at version " + v + ", not 6; nothing was changed");
            }
        }
        for (String t : NEW_TABLES) {
            if (count(c, "SELECT COUNT(*) FROM sqlite_master WHERE name = '" + t + "'") > 0) {
                throw new IllegalStateException("Table " + t + " already exists; nothing was changed");
            }
        }
        long duplicated = count(c, "SELECT COUNT(*) FROM (SELECT sale_id FROM payments GROUP BY sale_id HAVING COUNT(*) > 1)");
        if (duplicated > 0) {
            throw new IllegalStateException(duplicated + " sale(s) have more than one payment; ONE payment per sale "
                    + "cannot be enforced; nothing was changed (review these sales first)");
        }
        long unknown = count(c, "SELECT COUNT(*) FROM payments WHERE payment_method NOT IN "
                + "('CASH', 'CARD', 'WALLET', 'BANK_TRANSFER')");
        if (unknown > 0) {
            throw new IllegalStateException(unknown + " payment(s) with an unknown method; nothing was changed");
        }
    }

    static void writeBackup(Connection c, Path file, Map<String, Long> rows, Map<String, String> hashes,
                            Map<String, List<String>> columns) throws Exception {
        if (Files.exists(file)) {
            throw new IllegalStateException("Backup file " + file.getFileName() + " already exists; not overwritten");
        }
        exec(c, "VACUUM INTO '" + file.toString().replace("'", "''") + "'"); // outside any transaction
        try (Connection b = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            if (!"ok".equalsIgnoreCase(one(b, "PRAGMA integrity_check"))) {
                throw new IllegalStateException("Backup failed its integrity check");
            }
            if (!counts(b, rows.keySet()).equals(rows)) throw new IllegalStateException("Backup row counts differ");
            if (!hashes(b, columns).equals(hashes)) throw new IllegalStateException("Backup content differs");
        }
    }

    static void verifyAfter(Connection c, Map<String, Long> rows, Map<String, String> hashes,
                            Map<String, List<String>> columns, TreeSet<String> objectsBefore) throws Exception {
        if (!counts(c, rows.keySet()).equals(rows)) throw new IllegalStateException("Row counts changed; rolled back");
        if (!hashes(c, columns).equals(hashes)) throw new IllegalStateException("Existing data changed; rolled back");
        TreeSet<String> now = objects(c);
        for (String o : objectsBefore) {
            if (!now.contains(o)) throw new IllegalStateException("Lost " + o + "; rolled back");
        }
        for (String t : NEW_TABLES) {
            if (count(c, "SELECT COUNT(*) FROM " + t) != 0) throw new IllegalStateException(t + " not empty; rolled back");
        }
        if (count(c, "SELECT COUNT(*) FROM stores WHERE require_cash_shift IS NOT 0 OR cash_rounding_minor IS NOT 0") != 0
                || count(c, "SELECT COUNT(*) FROM sales WHERE rounding_minor IS NOT 0 OR shift_id IS NOT NULL") != 0
                || count(c, "SELECT COUNT(*) FROM sale_items WHERE units_per_package IS NOT 1 OR package_id IS NOT NULL") != 0
                || count(c, "SELECT COUNT(*) FROM payments WHERE tendered_minor IS NOT NULL") != 0) {
            throw new IllegalStateException("New columns did not get their neutral defaults; rolled back");
        }
        if (count(c, "SELECT COUNT(*) FROM pragma_foreign_key_check") > 0) {
            throw new IllegalStateException("Broken references after the change; rolled back");
        }
        if (!"ok".equalsIgnoreCase(one(c, "PRAGMA quick_check"))) {
            throw new IllegalStateException("quick_check failed after the change; rolled back");
        }
    }

    // ── helpers (same rules as V6) ──────────────────────────────────────────────────

    public static TreeSet<String> objects(Connection c) throws SQLException {
        TreeSet<String> s = new TreeSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT type || ':' || name FROM sqlite_master WHERE type IN ('index', 'trigger', 'table') "
                        + "AND name NOT LIKE 'sqlite_autoindex_%'")) {
            while (rs.next()) s.add(rs.getString(1));
        }
        return s;
    }

    static Long paymentSequence(Connection c) throws SQLException {
        if (count(c, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'sqlite_sequence'") == 0) return null;
        String v = one(c, "SELECT seq FROM sqlite_sequence WHERE name = 'payments'");
        return v == null ? null : Long.parseLong(v);
    }

    public static List<String> tables(Connection c) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master "
                + "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history' ORDER BY name")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    public static List<String> columns(Connection c, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT name FROM pragma_table_info('" + table.replace("'", "''") + "') ORDER BY cid")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    public static Map<String, Long> counts(Connection c, Iterable<String> tables) throws SQLException {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : tables) m.put(t, count(c, "SELECT COUNT(*) FROM \"" + t + "\""));
        return m;
    }

    /** SHA-256 of every value (type + quoted text) of the given columns, ordered by the first column. */
    public static Map<String, String> hashes(Connection c, Map<String, List<String>> columns) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : columns.entrySet()) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sel = new StringBuilder("SELECT ");
            for (int i = 0; i < e.getValue().size(); i++) {
                String col = "\"" + e.getValue().get(i) + "\"";
                if (i > 0) sel.append(", ");
                sel.append("typeof(").append(col).append("), quote(").append(col).append(")");
            }
            // ordered by rowid: identical for a rebuilt table copied in key order
            sel.append(" FROM \"").append(e.getKey()).append("\" ORDER BY rowid");
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sel.toString())) {
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

    public static String one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    public static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
