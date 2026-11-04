package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * V2: multi-store support.
 *
 * <ul>
 *   <li>New tables: {@code stores}, {@code users}, {@code user_sessions}.</li>
 *   <li>{@code suppliers} and {@code products} get a {@code store_id}; barcode and
 *       supplier name become unique <b>per store</b> instead of globally.</li>
 *   <li>Existing suppliers and products are moved to a store called "Default Store".</li>
 * </ul>
 *
 * <p>SQLite cannot change a table's constraints, so suppliers and products are
 * rebuilt (create new table → copy rows → drop old → rename), the procedure
 * recommended by SQLite. Before that, a backup copy of the database file is
 * written next to it. The whole change runs in one transaction and is rolled
 * back if any row count differs or any foreign key is broken.
 */
public class V2__multi_store extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V2__multi_store.class);

    /** Foreign keys must be switched off outside a transaction, so Flyway must not open one. */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        long products = count(c, "products");
        long suppliers = count(c, "suppliers");
        boolean hasData = products + suppliers > 0;

        if (hasData) {
            String backup = "clixsell-backup-before-v2-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db";
            exec(c, "VACUUM INTO '" + backup + "'");
            log.info("Database backup written to {}", backup);
        }

        boolean autoCommit = c.getAutoCommit();
        exec(c, "PRAGMA foreign_keys = OFF");
        c.setAutoCommit(false);
        try {
            exec(c, """
                    CREATE TABLE stores (
                        store_id    INTEGER PRIMARY KEY AUTOINCREMENT,
                        name        TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                        address     TEXT,
                        phone       TEXT,
                        status      TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
                        created_at  TEXT    NOT NULL
                    )""");
            if (hasData) {
                exec(c, "INSERT INTO stores (store_id, name, status, created_at) VALUES "
                        + "(1, 'Default Store', 'ACTIVE', strftime('%Y-%m-%dT%H:%M:%S', 'now', 'localtime'))");
            }

            exec(c, """
                    CREATE TABLE suppliers_new (
                        supplier_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        store_id         INTEGER NOT NULL REFERENCES stores (store_id),
                        name             TEXT    NOT NULL COLLATE NOCASE,
                        phone            TEXT,
                        whatsapp_number  TEXT,
                        address          TEXT,
                        UNIQUE (store_id, name)
                    )""");
            exec(c, """
                    INSERT INTO suppliers_new (supplier_id, store_id, name, phone, whatsapp_number, address)
                    SELECT supplier_id, 1, name, phone, whatsapp_number, address FROM suppliers""");

            exec(c, """
                    CREATE TABLE products_new (
                        product_id     INTEGER PRIMARY KEY AUTOINCREMENT,
                        store_id       INTEGER NOT NULL REFERENCES stores (store_id),
                        supplier_id    INTEGER NOT NULL REFERENCES suppliers (supplier_id),
                        name           TEXT    NOT NULL,
                        barcode        TEXT    NOT NULL,
                        category       TEXT    NOT NULL,
                        selling_price  REAL    NOT NULL CHECK (selling_price > 0),
                        quantity       INTEGER NOT NULL CHECK (quantity >= 0),
                        min_stock      INTEGER NOT NULL CHECK (min_stock >= 0),
                        image          BLOB,
                        image_name     TEXT,
                        created_at     TEXT    NOT NULL,
                        updated_at     TEXT    NOT NULL,
                        UNIQUE (store_id, barcode)
                    )""");
            exec(c, """
                    INSERT INTO products_new (product_id, store_id, supplier_id, name, barcode, category,
                                              selling_price, quantity, min_stock, image, image_name,
                                              created_at, updated_at)
                    SELECT product_id, 1, supplier_id, name, barcode, category,
                           selling_price, quantity, min_stock, image, image_name,
                           created_at, updated_at
                    FROM products""");

            exec(c, "DROP TABLE products");
            exec(c, "DROP TABLE suppliers");
            exec(c, "ALTER TABLE suppliers_new RENAME TO suppliers");
            exec(c, "ALTER TABLE products_new RENAME TO products");
            exec(c, "CREATE INDEX idx_products_supplier ON products (supplier_id)");

            exec(c, """
                    CREATE TABLE users (
                        user_id                INTEGER PRIMARY KEY AUTOINCREMENT,
                        store_id               INTEGER REFERENCES stores (store_id),
                        username               TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                        full_name              TEXT    NOT NULL,
                        password_hash          TEXT    NOT NULL,
                        role                   TEXT    NOT NULL CHECK (role IN ('SYSTEM_ADMIN', 'STORE_ADMIN', 'CASHIER')),
                        status                 TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
                        failed_login_attempts  INTEGER NOT NULL DEFAULT 0,
                        locked_until           TEXT,
                        created_at             TEXT    NOT NULL,
                        -- a System Admin belongs to no store; everyone else to exactly one
                        CHECK ((role = 'SYSTEM_ADMIN' AND store_id IS NULL)
                            OR (role <> 'SYSTEM_ADMIN' AND store_id IS NOT NULL))
                    )""");
            exec(c, "CREATE INDEX idx_users_store ON users (store_id)");

            exec(c, """
                    CREATE TABLE user_sessions (
                        session_id  INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id     INTEGER NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
                        token_hash  TEXT    NOT NULL UNIQUE,
                        created_at  TEXT    NOT NULL,
                        expires_at  TEXT    NOT NULL
                    )""");
            exec(c, "CREATE INDEX idx_sessions_user ON user_sessions (user_id)");

            // Safety checks: nothing lost, every link still valid.
            if (count(c, "products") != products || count(c, "suppliers") != suppliers) {
                throw new IllegalStateException("Row counts changed during migration; rolled back");
            }
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("PRAGMA foreign_key_check")) {
                if (rs.next()) {
                    throw new IllegalStateException("Broken foreign key in table " + rs.getString(1) + "; rolled back");
                }
            }
            c.commit();
            log.info("Multi-store migration done: {} products and {} suppliers moved to 'Default Store'",
                    products, suppliers);
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(autoCommit);
            exec(c, "PRAGMA foreign_keys = ON");
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
