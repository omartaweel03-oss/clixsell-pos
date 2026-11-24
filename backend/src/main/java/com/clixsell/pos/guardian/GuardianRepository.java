package com.clixsell.pos.guardian;

import com.clixsell.pos.guardian.GuardianData.CancelFact;
import com.clixsell.pos.guardian.GuardianData.InvoiceFact;
import com.clixsell.pos.guardian.GuardianData.LineFact;
import com.clixsell.pos.guardian.GuardianData.LogFact;
import com.clixsell.pos.guardian.GuardianData.ProductFact;
import com.clixsell.pos.guardian.GuardianData.ReturnFact;
import com.clixsell.pos.guardian.GuardianData.ShareLineFact;
import com.clixsell.pos.guardian.GuardianResponses.ActivityEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only queries for Guardian Mode. EVERY query is limited to one store
 * (the logged-in user's, given by the service) and reads only real records:
 * sales and their lines (with the sale-time cost), returns, canceled invoices,
 * products and the activity log. Dates are compared as ISO text, like the reports.
 */
@Repository
public class GuardianRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final JdbcTemplate db;

    public GuardianRepository(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * Text bound for "from the start of this day". Dates are stored as
     * LocalDateTime.toString(), which leaves out ":00" seconds (a sale at
     * midnight is "2026-11-23T00:00"). "…T00:00" compares correctly with both
     * forms, while "…T00:00:00" would put a midnight sale on the day before.
     */
    static String dayStart(LocalDate day) {
        return day + "T00:00";
    }

    /** Loads everything the rules need for the period [from, to] of one store. */
    public GuardianData load(long storeId, LocalDate from, LocalDate to, boolean currentPeriod, LocalDateTime now,
                             double maxDiscountPercent, LocalDateTime trackedSince) {
        String start = dayStart(from);
        String end = dayStart(to.plusDays(1));
        LocalDate today = now.toLocalDate();
        return new GuardianData(now, from, to, currentPeriod, maxDiscountPercent, trackedSince,
                products(storeId), invoices(storeId, start, end), belowCostLines(storeId, start, end),
                cancels(storeId, start, end), returns(storeId, start, end),
                saleLogs(storeId, "DISCOUNT_APPLIED", "COMPLETED", "completed_at", start, end),
                saleLogs(storeId, "INVOICE_CANCELED", "CANCELED", "updated_at", start, end),
                logs(storeId, "PRODUCT_DELETED", start, end),
                netUnitsSince(storeId, dayStart(today.minusDays(13))),
                soldSince(storeId, dayStart(today.minusDays(29))),
                // G8b: 14 days before the period, so a 7-day window may start in the previous month.
                returns(storeId, dayStart(from.minusDays(14)), end),
                discountLines(storeId, start, end));
    }

    /**
     * The same "units sold minus units returned in the last 14 days (today included)" that
     * {@link #load} gives Guardian Mode, for Purchase Recommendations (read only; Guardian's
     * own calculations are not changed).
     */
    public Map<Long, Long> netUnitsLast14(long storeId, LocalDate today) {
        return netUnitsSince(storeId, dayStart(today.minusDays(13)));
    }

    List<ProductFact> products(long storeId) {
        return db.query("""
                SELECT product_id, name, category, selling_price, cost_price, quantity, min_stock, created_at
                FROM products WHERE store_id = ? ORDER BY product_id""",
                (rs, i) -> new ProductFact(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4),
                        nullableDouble(rs, 5), rs.getInt(6), rs.getInt(7), time(rs.getString(8))),
                storeId);
    }

    List<InvoiceFact> invoices(long storeId, String start, String end) {
        return db.query("""
                SELECT s.sale_id, s.invoice_number, s.cashier_id, u.full_name, s.subtotal, s.discount, s.total,
                       s.completed_at, COUNT(si.sale_item_id),
                       COALESCE(SUM(CASE WHEN si.unit_cost IS NULL THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN si.unit_cost IS NOT NULL THEN si.unit_cost * si.quantity ELSE 0 END), 0),
                       COALESCE(SUM(si.quantity), 0), COALESCE(SUM(si.returned_quantity), 0), u.role
                FROM sales s
                JOIN users u ON u.user_id = s.cashier_id
                JOIN sale_items si ON si.sale_id = s.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND s.completed_at < ?
                  AND s.subtotal > 0
                GROUP BY s.sale_id
                ORDER BY s.completed_at, s.sale_id""",
                (rs, i) -> new InvoiceFact(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), time(rs.getString(8)),
                        rs.getInt(9), rs.getInt(10), rs.getDouble(11), rs.getInt(12), rs.getInt(13),
                        rs.getString(14)),
                storeId, start, end);
    }

    List<LineFact> belowCostLines(long storeId, String start, String end) {
        return db.query("""
                SELECT s.sale_id, s.invoice_number, si.product_id, si.product_name, si.quantity, si.unit_price,
                       si.unit_cost, s.completed_at, si.returned_quantity
                FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND s.completed_at < ?
                  AND si.unit_cost IS NOT NULL AND ROUND(si.unit_price, 2) < ROUND(si.unit_cost, 2)
                ORDER BY s.completed_at, si.sale_item_id""",
                (rs, i) -> new LineFact(rs.getLong(1), rs.getString(2), nullableLong(rs, 3), rs.getString(4),
                        rs.getInt(5), rs.getDouble(6), rs.getDouble(7), time(rs.getString(8)), rs.getInt(9)),
                storeId, start, end);
    }

    /** Canceled invoices; Sale.cancel() sets updated_at to the time of cancellation. */
    List<CancelFact> cancels(long storeId, String start, String end) {
        return db.query("""
                SELECT s.sale_id, s.invoice_number, s.total, s.cashier_id, u.full_name, s.updated_at
                FROM sales s JOIN users u ON u.user_id = s.cashier_id
                WHERE s.store_id = ? AND s.status = 'CANCELED' AND s.updated_at >= ? AND s.updated_at < ?
                ORDER BY s.updated_at, s.sale_id""",
                (rs, i) -> new CancelFact(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getLong(4),
                        rs.getString(5), time(rs.getString(6))),
                storeId, start, end);
    }

    /** Returns; returns.cashier_id is the user who processed the return (set by the backend). */
    List<ReturnFact> returns(long storeId, String start, String end) {
        return db.query("""
                SELECT r.return_id, r.sale_id, r.product_id, si.product_name, r.quantity, r.refund_amount,
                       r.return_date, r.cashier_id, u.full_name, u.role
                FROM returns r
                JOIN sale_items si ON si.sale_item_id = r.sale_item_id
                JOIN users u ON u.user_id = r.cashier_id
                WHERE r.store_id = ? AND r.return_date >= ? AND r.return_date < ?
                ORDER BY r.return_date, r.return_id""",
                (rs, i) -> new ReturnFact(rs.getLong(1), rs.getLong(2), nullableLong(rs, 3), rs.getString(4),
                        rs.getInt(5), rs.getDouble(6), time(rs.getString(7)), rs.getLong(8), rs.getString(9),
                        rs.getString(10)),
                storeId, start, end);
    }

    /**
     * Lines with a sale-time cost of the period's DISCOUNTED completed invoices
     * (G2c). The rule works out each line's share of the invoice discount.
     */
    List<ShareLineFact> discountLines(long storeId, String start, String end) {
        return db.query("""
                SELECT s.sale_id, s.invoice_number, si.product_id, si.product_name, si.quantity, si.unit_price,
                       si.unit_cost, si.line_total, s.subtotal, s.discount, s.completed_at, si.returned_quantity
                FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND s.completed_at < ?
                  AND s.subtotal > 0 AND s.discount > 0 AND si.unit_cost IS NOT NULL
                ORDER BY s.completed_at, si.sale_item_id""",
                (rs, i) -> new ShareLineFact(rs.getLong(1), rs.getString(2), nullableLong(rs, 3), rs.getString(4),
                        rs.getInt(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9),
                        rs.getDouble(10), time(rs.getString(11)), rs.getInt(12)),
                storeId, start, end);
    }

    /** Log rows of one type for the store's invoices of the period (by sale id). */
    Map<Long, LogFact> saleLogs(long storeId, String type, String status, String dateColumn, String start, String end) {
        Map<Long, LogFact> out = new HashMap<>();
        db.query("""
                SELECT l.log_id, l.event_type, l.user_id, u.full_name, l.entity_id, l.entity_name, l.details, l.created_at,
                       u.role
                FROM activity_log l JOIN users u ON u.user_id = l.user_id
                WHERE l.store_id = ? AND l.event_type = ? AND l.entity_type = 'SALE'
                  AND l.entity_id IN (SELECT sale_id FROM sales WHERE store_id = ? AND status = ?
                                      AND %s >= ? AND %s < ?)
                ORDER BY l.log_id""".formatted(dateColumn, dateColumn),
                rs -> {
                    LogFact f = log(rs);
                    out.put(f.entityId(), f); // the latest row wins
                }, storeId, type, storeId, status, start, end);
        return out;
    }

    List<LogFact> logs(long storeId, String type, String start, String end) {
        return db.query("""
                SELECT l.log_id, l.event_type, l.user_id, u.full_name, l.entity_id, l.entity_name, l.details, l.created_at,
                       u.role
                FROM activity_log l JOIN users u ON u.user_id = l.user_id
                WHERE l.store_id = ? AND l.event_type = ? AND l.created_at >= ? AND l.created_at < ?
                ORDER BY l.created_at, l.log_id""",
                (rs, i) -> log(rs), storeId, type, start, end);
    }

    /** Units sold (completed sales) minus units returned since {@code since}, by product. */
    Map<Long, Long> netUnitsSince(long storeId, String since) {
        Map<Long, Long> out = new HashMap<>();
        db.query("""
                SELECT si.product_id, SUM(si.quantity)
                FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND si.product_id IS NOT NULL
                GROUP BY si.product_id""",
                rs -> {
                    out.merge(rs.getLong(1), rs.getLong(2), Long::sum);
                }, storeId, since);
        db.query("""
                SELECT product_id, SUM(quantity) FROM returns
                WHERE store_id = ? AND return_date >= ? AND product_id IS NOT NULL
                GROUP BY product_id""",
                rs -> {
                    out.merge(rs.getLong(1), -rs.getLong(2), Long::sum);
                }, storeId, since);
        return out;
    }

    Set<Long> soldSince(long storeId, String since) {
        return new HashSet<>(db.queryForList("""
                SELECT DISTINCT si.product_id
                FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND si.product_id IS NOT NULL
                  AND si.quantity > 0""", Long.class, storeId, since));
    }

    /** Net sales (paid totals − refunds) from the start of {@code day} up to {@code to} (included). */
    double netSales(long storeId, LocalDate day, LocalDateTime to) {
        Double sales = db.queryForObject("""
                SELECT COALESCE(SUM(total), 0) FROM sales
                WHERE store_id = ? AND status = 'COMPLETED' AND completed_at >= ? AND completed_at <= ?""",
                Double.class, storeId, dayStart(day), TEXT.format(to));
        Double refunds = db.queryForObject("""
                SELECT COALESCE(SUM(refund_amount), 0) FROM returns
                WHERE store_id = ? AND return_date >= ? AND return_date <= ?""",
                Double.class, storeId, dayStart(day), TEXT.format(to));
        return (sales == null ? 0 : sales) - (refunds == null ? 0 : refunds);
    }

    /**
     * When the activity log started for this store: when migration V5 was
     * installed (Flyway stores that time in UTC), or when the store was created
     * if that is later.
     */
    LocalDateTime trackedSince(long storeId) {
        List<String> installed = db.queryForList(
                "SELECT installed_on FROM flyway_schema_history WHERE version = '5' AND success = 1", String.class);
        LocalDateTime v5 = null;
        if (!installed.isEmpty() && installed.get(0) != null) {
            v5 = flywayTimeToLocal(installed.get(0), ZoneId.systemDefault());
        }
        List<String> created = db.queryForList("SELECT created_at FROM stores WHERE store_id = ?", String.class, storeId);
        LocalDateTime store = created.isEmpty() || created.get(0) == null ? null : time(created.get(0));
        if (v5 == null) return store;
        if (store == null) return v5;
        return store.isAfter(v5) ? store : v5;
    }

    /**
     * Flyway writes installed_on in UTC ("2026-11-23 00:01:52.123" in SQLite);
     * the app stores local times. Converts to the server's local time zone
     * (e.g. Asia/Jerusalem on a Windows PC in Palestine).
     */
    static LocalDateTime flywayTimeToLocal(String utcText, ZoneId zone) {
        return LocalDateTime.parse(utcText.trim().replace(' ', 'T'))
                .atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime().withNano(0);
    }

    /** The last Guardian on / off of the store: [time, user name], or null. */
    Object[] lastToggle(long storeId) {
        List<Object[]> rows = db.query("""
                SELECT l.created_at, u.full_name FROM activity_log l JOIN users u ON u.user_id = l.user_id
                WHERE l.store_id = ? AND l.event_type IN ('GUARDIAN_ENABLED', 'GUARDIAN_DISABLED')
                ORDER BY l.log_id DESC LIMIT 1""",
                (rs, i) -> new Object[]{time(rs.getString(1)), rs.getString(2)}, storeId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    long activityCount(long storeId, String start, String end, String type) {
        Long n = type == null
                ? db.queryForObject("SELECT COUNT(*) FROM activity_log WHERE store_id = ? AND created_at >= ? AND created_at < ?",
                        Long.class, storeId, start, end)
                : db.queryForObject("SELECT COUNT(*) FROM activity_log WHERE store_id = ? AND created_at >= ? AND created_at < ? "
                        + "AND event_type = ?", Long.class, storeId, start, end, type);
        return n == null ? 0 : n;
    }

    List<ActivityEntry> activity(long storeId, String start, String end, String type, int limit, int offset) {
        String sql = """
                SELECT l.log_id, l.event_type, l.created_at, l.user_id, u.full_name, l.entity_type, l.entity_id,
                       l.entity_name, l.details
                FROM activity_log l JOIN users u ON u.user_id = l.user_id
                WHERE l.store_id = ? AND l.created_at >= ? AND l.created_at < ? %s
                ORDER BY l.created_at DESC, l.log_id DESC LIMIT ? OFFSET ?""".formatted(type == null ? "" : "AND l.event_type = ?");
        Object[] args = type == null ? new Object[]{storeId, start, end, limit, offset}
                : new Object[]{storeId, start, end, type, limit, offset};
        return db.query(sql, (rs, i) -> new ActivityEntry(rs.getLong(1), rs.getString(2), time(rs.getString(3)),
                rs.getLong(4), rs.getString(5), rs.getString(6), nullableLong(rs, 7), rs.getString(8),
                details(rs.getString(9))), args);
    }

    private static LogFact log(ResultSet rs) throws SQLException {
        return new LogFact(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), nullableLong(rs, 5),
                rs.getString(6), details(rs.getString(7)), time(rs.getString(8)), rs.getString(9));
    }

    static Map<String, Object> details(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static LocalDateTime time(String text) {
        return text == null ? null : LocalDateTime.parse(text);
    }

    private static Long nullableLong(ResultSet rs, int col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Double nullableDouble(ResultSet rs, int col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    static List<Long> ids(List<Long> list) {
        return new ArrayList<>(list);
    }
}
