package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V5: Guardian Mode, Phase 1 (report Figure 3.1, UR-M16 / SR30, UR-M17 / SR31).
 *
 * <ul>
 *   <li>{@code stores.guardian_enabled} – Guardian Mode on (1) or off (0) for each
 *       store. Every store, existing or new, starts OFF: only its Store Admin
 *       turns it on.</li>
 *   <li>{@code activity_log} – who did what, recorded by the backend with the
 *       logged-in user: discounts, canceled invoices, deleted products, price /
 *       cost / stock changes and Guardian on / off. Nothing before this version
 *       is invented: the log starts empty.</li>
 * </ul>
 *
 * <p>Only additions: one column with a default (SQLite "ADD COLUMN" does not
 * rebuild or touch any row), one new table and its indexes. Before the change a
 * backup copy of the database file is written and CHECKED (integrity check and
 * the same number of rows in every table); if the backup is not valid nothing is
 * changed. The change itself is rolled back if any table's row count changes.
 */
public class V5__guardian_mode extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V5__guardian_mode.class);

    static final String BACKUP_PREFIX = "clixsell-backup-before-v5-";

    /** VACUUM INTO (the backup) cannot run inside a transaction, so this migration manages its own. */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        Map<String, Long> before = counts(c, tables(c));
        if (before.values().stream().anyMatch(n -> n > 0)) {
            String backup = BACKUP_PREFIX
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db";
            exec(c, "VACUUM INTO '" + backup + "'");
            verifyBackup(backup, before);
            log.info("Database backup written and checked: {}", Path.of(backup).toAbsolutePath());
        }

        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            exec(c, "ALTER TABLE stores ADD COLUMN guardian_enabled INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (guardian_enabled IN (0, 1))");
            exec(c, """
                    CREATE TABLE activity_log (
                        log_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        store_id    INTEGER NOT NULL REFERENCES stores (store_id),
                        user_id     INTEGER NOT NULL REFERENCES users (user_id),
                        event_type  TEXT    NOT NULL CHECK (event_type IN ('DISCOUNT_APPLIED', 'INVOICE_CANCELED',
                                        'PRODUCT_DELETED', 'PRICE_CHANGED', 'COST_CHANGED', 'STOCK_ADJUSTED',
                                        'GUARDIAN_ENABLED', 'GUARDIAN_DISABLED')),
                        entity_type TEXT    CHECK (entity_type IS NULL OR entity_type IN ('SALE', 'PRODUCT', 'STORE')),
                        entity_id   INTEGER,
                        entity_name TEXT,
                        details     TEXT,
                        created_at  TEXT    NOT NULL
                    )""");
            exec(c, "CREATE INDEX idx_activity_store_time ON activity_log (store_id, created_at)");
            exec(c, "CREATE INDEX idx_activity_store_type ON activity_log (store_id, event_type, created_at)");

            Map<String, Long> after = counts(c, before.keySet());
            if (!after.equals(before)) {
                throw new IllegalStateException("Row counts changed during migration " + before + " → " + after
                        + "; rolled back");
            }
            if (count(c, "SELECT COUNT(*) FROM stores WHERE guardian_enabled <> 0") != 0) {
                throw new IllegalStateException("Guardian Mode must start OFF for every store; rolled back");
            }
            c.commit();
            log.info("Guardian Mode migration done (every store OFF); existing rows kept: {}", before);
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(autoCommit);
        }
    }

    /**
     * Opens the backup copy on its own and checks it before anything is changed:
     * SQLite integrity check "ok" and exactly the same number of rows in every table.
     */
    static void verifyBackup(String file, Map<String, Long> expected) throws SQLException {
        try (Connection b = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = b.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
            String result = rs.next() ? rs.getString(1) : null;
            if (!"ok".equalsIgnoreCase(result)) {
                throw new IllegalStateException("Backup " + file + " failed the integrity check (" + result
                        + "); the database was not changed");
            }
            Map<String, Long> copied = counts(b, expected.keySet());
            if (!copied.equals(expected)) {
                throw new IllegalStateException("Backup " + file + " does not hold every row " + expected + " → "
                        + copied + "; the database was not changed");
            }
        }
    }

    /** Every table of the database except SQLite's and Flyway's own. */
    private static List<String> tables(Connection c) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' "
                     + "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history' ORDER BY name")) {
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    private static Map<String, Long> counts(Connection c, Iterable<String> tables) throws SQLException {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : tables) m.put(t, count(c, "SELECT COUNT(*) FROM \"" + t + "\""));
        return m;
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
