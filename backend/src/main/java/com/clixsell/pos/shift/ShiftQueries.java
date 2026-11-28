package com.clixsell.pos.shift;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQL for cash drawers, shifts and cash movements (V7 tables). Every query is limited to ONE store;
 * the store id always comes from the logged-in user, never from the request.
 */
@Repository
public class ShiftQueries {

    private final JdbcTemplate db;

    public ShiftQueries(JdbcTemplate db) {
        this.db = db;
    }

    // ── drawers ─────────────────────────────────────────────────────────────────────

    public record Drawer(long drawerId, String name, boolean active, boolean inUse) {
    }

    public List<Drawer> drawers(long storeId) {
        return db.query("""
                SELECT d.drawer_id, d.name, d.active,
                       EXISTS (SELECT 1 FROM cash_shifts s WHERE s.drawer_id = d.drawer_id AND s.status = 'OPEN')
                FROM cash_drawers d WHERE d.store_id = ? ORDER BY d.name COLLATE NOCASE, d.drawer_id""",
                (rs, i) -> new Drawer(rs.getLong(1), rs.getString(2), rs.getInt(3) == 1, rs.getInt(4) == 1), storeId);
    }

    public Optional<Drawer> drawer(long storeId, long drawerId) {
        return drawers(storeId).stream().filter(d -> d.drawerId() == drawerId).findFirst();
    }

    public boolean drawerNameTaken(long storeId, String name, Long exceptId) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM cash_drawers WHERE store_id = ? AND name = ? COLLATE NOCASE "
                + "AND drawer_id IS NOT ?", Integer.class, storeId, name, exceptId);
        return n != null && n > 0;
    }

    public long createDrawer(long storeId, String name, String now) {
        return insert("INSERT INTO cash_drawers (store_id, name, active, created_at) VALUES (?, ?, 1, ?)",
                storeId, name, now);
    }

    public int updateDrawer(long storeId, long drawerId, String name, boolean active) {
        return db.update("UPDATE cash_drawers SET name = ?, active = ? WHERE drawer_id = ? AND store_id = ?",
                name, active ? 1 : 0, drawerId, storeId);
    }

    // ── shifts ──────────────────────────────────────────────────────────────────────

    public record Shift(long shiftId, long storeId, long drawerId, String drawerName, long cashierId,
                        String cashierName, String status, String openedAt, long openingCashMinor, String closedAt,
                        Long closedBy, String closedByName, String closeKind, String closeReason,
                        Long expectedCashMinor, Long countedCashMinor, Long differenceMinor, String closingNote,
                        long version) {
    }

    private static final String SHIFT_SELECT = """
            SELECT s.shift_id, s.store_id, s.drawer_id, d.name, s.cashier_id, cu.full_name, s.status, s.opened_at,
                   s.opening_cash_minor, s.closed_at, s.closed_by, cb.full_name, s.close_kind, s.close_reason,
                   s.expected_cash_minor, s.counted_cash_minor, s.difference_minor, s.closing_note, s.version
            FROM cash_shifts s
            JOIN cash_drawers d ON d.drawer_id = s.drawer_id
            JOIN users cu ON cu.user_id = s.cashier_id
            LEFT JOIN users cb ON cb.user_id = s.closed_by
            """;

    private static Shift shift(ResultSet rs, int i) throws SQLException {
        return new Shift(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getLong(9), rs.getString(10), nullableLong(rs, 11),
                rs.getString(12), rs.getString(13), rs.getString(14), nullableLong(rs, 15),
                nullableLong(rs, 16), nullableLong(rs, 17), rs.getString(18), rs.getLong(19));
    }

    /** The SQLite driver cannot read NULL as getObject(i, Long.class). */
    private static Long nullableLong(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    public Optional<Shift> shift(long storeId, long shiftId) {
        return db.query(SHIFT_SELECT + " WHERE s.shift_id = ? AND s.store_id = ?", ShiftQueries::shift, shiftId, storeId)
                .stream().findFirst();
    }

    /** The user's open shift in the store, if any (at most one: unique index). */
    public Optional<Shift> openShiftOf(long storeId, long userId) {
        return db.query(SHIFT_SELECT + " WHERE s.store_id = ? AND s.cashier_id = ? AND s.status = 'OPEN'",
                ShiftQueries::shift, storeId, userId).stream().findFirst();
    }

    public List<Shift> shifts(long storeId, String status, String fromInclusive, String toExclusive, int limit) {
        StringBuilder where = new StringBuilder(" WHERE s.store_id = ?");
        List<Object> args = new ArrayList<>(List.of(storeId));
        if (status != null) {
            where.append(" AND s.status = ?");
            args.add(status);
        }
        if (fromInclusive != null) {
            where.append(" AND s.opened_at >= ?");
            args.add(fromInclusive);
        }
        if (toExclusive != null) {
            where.append(" AND s.opened_at < ?");
            args.add(toExclusive);
        }
        args.add(limit);
        return db.query(SHIFT_SELECT + where + " ORDER BY s.shift_id DESC LIMIT ?", ShiftQueries::shift, args.toArray());
    }

    public long openShift(long storeId, long drawerId, long cashierId, long openingMinor, String now) {
        return insert("INSERT INTO cash_shifts (store_id, drawer_id, cashier_id, status, opened_at, opening_cash_minor) "
                + "VALUES (?, ?, ?, 'OPEN', ?, ?)", storeId, drawerId, cashierId, now, openingMinor);
    }

    /** The parts of the expected cash, with the same formulas as the database's closing check. */
    public record CashParts(long openingMinor, long cashSalesMinor, int cashSalesCount, long cashRefundsMinor,
                            int cashRefundsCount, long cashInMinor, long cashOutMinor, long chequeRefundsPaidMinor,
                            long otherSalesMinor, int otherSalesCount) {
        public long expectedMinor() {
            return openingMinor + cashSalesMinor - cashRefundsMinor + cashInMinor - cashOutMinor - chequeRefundsPaidMinor;
        }
    }

    public CashParts cashParts(long shiftId) {
        return db.queryForObject("""
                SELECT s.opening_cash_minor,
                  coalesce((SELECT sum(CAST(round(p.amount * 100) AS INTEGER)) FROM payments p JOIN sales x ON x.sale_id = p.sale_id
                            WHERE x.shift_id = s.shift_id AND p.payment_method = 'CASH'), 0),
                  (SELECT COUNT(*) FROM payments p JOIN sales x ON x.sale_id = p.sale_id
                    WHERE x.shift_id = s.shift_id AND p.payment_method = 'CASH'),
                  coalesce((SELECT sum(coalesce(r.refund_minor, CAST(round(r.refund_amount * 100) AS INTEGER)))
                            FROM returns r WHERE r.shift_id = s.shift_id AND r.refund_method = 'CASH'), 0),
                  (SELECT COUNT(*) FROM returns r WHERE r.shift_id = s.shift_id AND r.refund_method = 'CASH'),
                  coalesce((SELECT sum(amount_minor) FROM cash_movements WHERE shift_id = s.shift_id AND type = 'CASH_IN'), 0),
                  coalesce((SELECT sum(amount_minor) FROM cash_movements WHERE shift_id = s.shift_id AND type = 'CASH_OUT'), 0),
                  coalesce((SELECT sum(amount_minor) FROM cash_movements WHERE shift_id = s.shift_id AND type = 'CHEQUE_REFUND_OUT'), 0),
                  coalesce((SELECT sum(CAST(round(p.amount * 100) AS INTEGER)) FROM payments p JOIN sales x ON x.sale_id = p.sale_id
                            WHERE x.shift_id = s.shift_id AND p.payment_method <> 'CASH'), 0),
                  (SELECT COUNT(*) FROM payments p JOIN sales x ON x.sale_id = p.sale_id
                    WHERE x.shift_id = s.shift_id AND p.payment_method <> 'CASH')
                FROM cash_shifts s WHERE s.shift_id = ?""",
                (rs, i) -> new CashParts(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getLong(4), rs.getInt(5),
                        rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getInt(10)), shiftId);
    }

    /**
     * Closes an OPEN shift at the version that was read. 0 rows = someone else closed it first.
     * The database re-checks the expected amount (trigger) and refuses a stale one.
     */
    public int close(long storeId, long shiftId, long version, String now, long closedBy, String kind, String reason,
                     long expectedMinor, long countedMinor, String note) {
        return db.update("""
                UPDATE cash_shifts SET status = 'CLOSED', closed_at = ?, closed_by = ?, close_kind = ?, close_reason = ?,
                       expected_cash_minor = ?, counted_cash_minor = ?, difference_minor = ?, closing_note = ?,
                       version = version + 1
                WHERE shift_id = ? AND store_id = ? AND status = 'OPEN' AND version = ?""",
                now, closedBy, kind, reason, expectedMinor, countedMinor, countedMinor - expectedMinor, note,
                shiftId, storeId, version);
    }

    // ── cash movements ──────────────────────────────────────────────────────────────

    public record Movement(long movementId, String type, long amountMinor, String reason, long createdBy,
                           String createdByName, String createdAt, String requestKey) {
    }

    public List<Movement> movements(long shiftId) {
        return db.query("""
                SELECT m.movement_id, m.type, m.amount_minor, m.reason, m.created_by, u.full_name, m.created_at, m.request_key
                FROM cash_movements m JOIN users u ON u.user_id = m.created_by
                WHERE m.shift_id = ? ORDER BY m.movement_id""",
                (rs, i) -> new Movement(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)), shiftId);
    }

    public Optional<Movement> movementByKey(long storeId, String requestKey) {
        return db.query("""
                SELECT m.movement_id, m.type, m.amount_minor, m.reason, m.created_by, u.full_name, m.created_at, m.request_key
                FROM cash_movements m JOIN users u ON u.user_id = m.created_by
                WHERE m.store_id = ? AND m.request_key = ?""",
                (rs, i) -> new Movement(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                        rs.getString(6), rs.getString(7), rs.getString(8)), storeId, requestKey).stream().findFirst();
    }

    public Long movementShift(long movementId) {
        return db.queryForObject("SELECT shift_id FROM cash_movements WHERE movement_id = ?", Long.class, movementId);
    }

    public long addMovement(long storeId, long shiftId, String type, long amountMinor, String reason, long createdBy,
                            String now, String requestKey) {
        return insert("INSERT INTO cash_movements (store_id, shift_id, type, amount_minor, reason, created_by, created_at, "
                + "request_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", storeId, shiftId, type, amountMinor, reason, createdBy, now,
                requestKey);
    }

    /** Sales of a shift (invoice list in the shift report). */
    public record ShiftSale(long saleId, String invoiceNumber, String completedAt, String paymentMethod, double total) {
    }

    public List<ShiftSale> sales(long shiftId) {
        return db.query("""
                SELECT x.sale_id, x.invoice_number, x.completed_at, p.payment_method, p.amount
                FROM sales x JOIN payments p ON p.sale_id = x.sale_id
                WHERE x.shift_id = ? ORDER BY x.sale_id""",
                (rs, i) -> new ShiftSale(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5)),
                shiftId);
    }

    /** Takes SQLite's write lock without changing anything (first statement of a transaction). */
    public void takeWriteLock() {
        db.update("UPDATE stores SET store_id = store_id WHERE 0");
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        db.update(c -> {
            PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("No id returned for: " + sql);
        return key.longValue();
    }
}
