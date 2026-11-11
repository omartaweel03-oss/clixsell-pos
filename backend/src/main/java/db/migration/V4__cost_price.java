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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V4: purchase cost, for accurate profit (report ERD, page 46).
 *
 * <ul>
 *   <li>{@code products.cost_price} – the product's purchase cost (optional, ≥ 0).</li>
 *   <li>{@code sale_items.unit_cost} – the cost copied onto each sold line when
 *       the sale is completed, so later cost changes never change past profit.</li>
 * </ul>
 *
 * <p>Only two nullable columns are ADDED (SQLite "ADD COLUMN" does not rebuild
 * or touch any row). Existing products and sale lines keep NULL: their cost is
 * unknown and is never guessed. A backup copy of the database file is written
 * first, and the migration is rolled back if any table's row count changes.
 */
public class V4__cost_price extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V4__cost_price.class);

    static final String[] TABLES = {"stores", "users", "suppliers", "products", "sales", "sale_items",
            "payments", "receipts", "returns"};

    /** VACUUM INTO (the backup) cannot run inside a transaction, so this migration manages its own. */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        Map<String, Long> before = counts(c);
        if (before.values().stream().anyMatch(n -> n > 0)) {
            String backup = "clixsell-backup-before-v4-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".db";
            exec(c, "VACUUM INTO '" + backup + "'");
            log.info("Database backup written to {}", backup);
        }

        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            exec(c, "ALTER TABLE products ADD COLUMN cost_price REAL "
                    + "CHECK (cost_price IS NULL OR cost_price >= 0)");
            exec(c, "ALTER TABLE sale_items ADD COLUMN unit_cost REAL "
                    + "CHECK (unit_cost IS NULL OR unit_cost >= 0)");
            Map<String, Long> after = counts(c);
            if (!after.equals(before)) {
                throw new IllegalStateException("Row counts changed during migration " + before + " → " + after
                        + "; rolled back");
            }
            c.commit();
            log.info("Cost price migration done; existing rows kept: {}", before);
        } catch (Exception e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(autoCommit);
        }
    }

    private static Map<String, Long> counts(Connection c) throws SQLException {
        Map<String, Long> m = new LinkedHashMap<>();
        for (String t : TABLES) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + t)) {
                m.put(t, rs.next() ? rs.getLong(1) : 0);
            }
        }
        return m;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
