package com.clixsell.pos.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * Read-only reporting queries over the existing sales tables. Every query is
 * limited to one store and to COMPLETED sales (suspended and canceled
 * invoices are never counted). Dates are compared as ISO text.
 */
@Repository
public class ReportRepository {

    private final JdbcTemplate db;

    public ReportRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** Totals of the completed invoices whose completion time is in the period. */
    public SalesTotals salesTotals(long storeId, ReportPeriod p) {
        return db.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(total), 0), COALESCE(SUM(discount), 0), COALESCE(SUM(tax), 0)
                FROM sales
                WHERE store_id = ? AND status = 'COMPLETED' AND completed_at >= ? AND completed_at < ?""",
                (rs, i) -> new SalesTotals(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4)),
                storeId, p.startText(), p.endTextExclusive());
    }

    /** Refunds of the returns made in the period. */
    public RefundTotals refundTotals(long storeId, ReportPeriod p) {
        return db.queryForObject("""
                SELECT COUNT(*), COALESCE(SUM(refund_amount), 0), COALESCE(SUM(quantity), 0)
                FROM returns
                WHERE store_id = ? AND return_date >= ? AND return_date < ?""",
                (rs, i) -> new RefundTotals(rs.getLong(1), rs.getDouble(2), rs.getLong(3)),
                storeId, p.startText(), p.endTextExclusive());
    }

    /** Units sold in the period's completed invoices. */
    public long unitsSold(long storeId, ReportPeriod p) {
        Long n = db.queryForObject("""
                SELECT COALESCE(SUM(si.quantity), 0)
                FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND s.completed_at < ?""",
                Long.class, storeId, p.startText(), p.endTextExclusive());
        return n == null ? 0 : n;
    }

    /**
     * Sales and refunds grouped by a prefix of the date text:
     * 10 characters = day (2026-11-04), 7 characters = month (2026-11).
     */
    public Map<String, double[]> grouped(long storeId, ReportPeriod p, int prefix) {
        Map<String, double[]> out = new java.util.HashMap<>();
        db.query("""
                SELECT substr(completed_at, 1, ?) AS k, SUM(total), COUNT(*)
                FROM sales
                WHERE store_id = ? AND status = 'COMPLETED' AND completed_at >= ? AND completed_at < ?
                GROUP BY k""",
                rs -> {
                    double[] v = out.computeIfAbsent(rs.getString(1), k -> new double[3]);
                    v[0] += rs.getDouble(2);
                    v[2] += rs.getLong(3);
                }, prefix, storeId, p.startText(), p.endTextExclusive());
        db.query("""
                SELECT substr(return_date, 1, ?) AS k, SUM(refund_amount)
                FROM returns
                WHERE store_id = ? AND return_date >= ? AND return_date < ?
                GROUP BY k""",
                rs -> {
                    double[] v = out.computeIfAbsent(rs.getString(1), k -> new double[3]);
                    v[1] += rs.getDouble(2);
                }, prefix, storeId, p.startText(), p.endTextExclusive());
        return out;
    }

    /**
     * Products of the period's completed invoices: units still kept by the
     * customers (sold − returned) and their share of what was paid, using
     * the same rule as refunds: total × line_total / subtotal × kept / quantity
     * (so the invoice discount and the tax are included in proportion).
     * Deleted products are grouped by the name stored on the invoice.
     */
    public List<ProductSales> productSales(long storeId, ReportPeriod p) {
        return db.query("""
                SELECT si.product_id,
                       MAX(si.product_name),
                       SUM(si.quantity - si.returned_quantity) AS units,
                       SUM(s.total * si.line_total / s.subtotal
                           * (si.quantity - si.returned_quantity) / si.quantity) AS value
                FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND s.completed_at < ?
                  AND s.subtotal > 0
                GROUP BY COALESCE(CAST(si.product_id AS TEXT), 'name:' || si.product_name)
                HAVING units > 0
                ORDER BY value DESC, units DESC, MAX(si.product_name)""",
                (rs, i) -> new ProductSales(
                        rs.getObject(1) == null ? null : rs.getLong(1),
                        rs.getString(2), rs.getLong(3), rs.getDouble(4)),
                storeId, p.startText(), p.endTextExclusive());
    }

    public record SalesTotals(long invoices, double total, double discount, double tax) {
    }

    public record RefundTotals(long returns, double refunds, long units) {
    }

    public record ProductSales(Long productId, String name, long units, double value) {
    }
}
