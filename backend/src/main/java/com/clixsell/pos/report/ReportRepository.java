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
     * Product sales of the period, by event date: units and paid value of the
     * completed invoices of the period, minus the units and refunds of the
     * returns made in the period. Deleted products are grouped by the name
     * stored on the invoice line.
     */
    public List<ProductSales> productSales(long storeId, ReportPeriod p) {
        Map<String, ProductSales> byKey = new java.util.LinkedHashMap<>();
        db.query("""
                SELECT si.product_id, si.product_name, SUM(si.quantity), SUM(s.total * si.line_total / s.subtotal)
                FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND s.completed_at < ?
                  AND s.subtotal > 0
                GROUP BY COALESCE(CAST(si.product_id AS TEXT), 'name:' || si.product_name)""",
                rs -> {
                    Long id = rs.getObject(1) == null ? null : rs.getLong(1);
                    add(byKey, id, rs.getString(2), rs.getLong(3), rs.getDouble(4));
                }, storeId, p.startText(), p.endTextExclusive());
        db.query("""
                SELECT si.product_id, si.product_name, SUM(r.quantity), SUM(r.refund_amount)
                FROM returns r JOIN sale_items si ON si.sale_item_id = r.sale_item_id
                WHERE r.store_id = ? AND r.return_date >= ? AND r.return_date < ?
                GROUP BY COALESCE(CAST(si.product_id AS TEXT), 'name:' || si.product_name)""",
                rs -> {
                    Long id = rs.getObject(1) == null ? null : rs.getLong(1);
                    add(byKey, id, rs.getString(2), -rs.getLong(3), -rs.getDouble(4));
                }, storeId, p.startText(), p.endTextExclusive());
        List<ProductSales> out = new java.util.ArrayList<>(byKey.values());
        out.sort(java.util.Comparator.comparingDouble(ProductSales::value).reversed()
                .thenComparing(java.util.Comparator.comparingLong(ProductSales::units).reversed())
                .thenComparing(ProductSales::name));
        return out;
    }

    private static void add(Map<String, ProductSales> m, Long id, String name, long units, double value) {
        String key = id != null ? id.toString() : "name:" + name;
        m.merge(key, new ProductSales(id, name, units, value),
                (a, b) -> new ProductSales(a.productId(), a.name(), a.units() + b.units(), a.value() + b.value()));
    }

    /**
     * Revenue without tax and purchase cost of the period, for profit.
     * Revenue of a line = line_total × (subtotal − discount) / subtotal: the
     * invoice discount is shared once, the tax is never included.
     * Sales of the period count by completion date; returns of the period are
     * subtracted by their own date, with the cost saved on the sold line.
     * Lines without a saved cost are counted separately (never guessed).
     */
    public ProfitParts profitParts(long storeId, ReportPeriod p) {
        ProfitParts sold = db.queryForObject("""
                SELECT COUNT(*),
                       COALESCE(SUM(CASE WHEN si.unit_cost IS NULL THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(si.line_total * (s.subtotal - s.discount) / s.subtotal), 0),
                       COALESCE(SUM(CASE WHEN si.unit_cost IS NOT NULL
                                         THEN si.line_total * (s.subtotal - s.discount) / s.subtotal END), 0),
                       COALESCE(SUM(si.unit_cost * si.quantity), 0)
                FROM sale_items si JOIN sales s ON s.sale_id = si.sale_id
                WHERE s.store_id = ? AND s.status = 'COMPLETED' AND s.completed_at >= ? AND s.completed_at < ?
                  AND s.subtotal > 0""",
                (rs, i) -> new ProfitParts(rs.getLong(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5)),
                storeId, p.startText(), p.endTextExclusive());
        ProfitParts returned = db.queryForObject("""
                SELECT COUNT(*),
                       COALESCE(SUM(CASE WHEN si.unit_cost IS NULL THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(si.line_total * (s.subtotal - s.discount) / s.subtotal * r.quantity / si.quantity), 0),
                       COALESCE(SUM(CASE WHEN si.unit_cost IS NOT NULL
                                         THEN si.line_total * (s.subtotal - s.discount) / s.subtotal
                                              * r.quantity / si.quantity END), 0),
                       COALESCE(SUM(si.unit_cost * r.quantity), 0)
                FROM returns r
                JOIN sale_items si ON si.sale_item_id = r.sale_item_id
                JOIN sales s ON s.sale_id = r.sale_id
                WHERE r.store_id = ? AND r.return_date >= ? AND r.return_date < ? AND s.subtotal > 0""",
                (rs, i) -> new ProfitParts(rs.getLong(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5)),
                storeId, p.startText(), p.endTextExclusive());
        return new ProfitParts(sold.lines() + returned.lines(), sold.linesWithoutCost() + returned.linesWithoutCost(),
                sold.revenue() - returned.revenue(), sold.coveredRevenue() - returned.coveredRevenue(),
                sold.cost() - returned.cost());
    }

    public record SalesTotals(long invoices, double total, double discount, double tax) {
    }

    public record RefundTotals(long returns, double refunds, long units) {
    }

    public record ProductSales(Long productId, String name, long units, double value) {
    }

    /**
     * @param lines            sold lines + return records of the period
     * @param linesWithoutCost of those, how many have no saved purchase cost
     * @param revenue          net revenue without tax (sales − returns)
     * @param coveredRevenue   the part of {@code revenue} whose cost is known
     * @param cost             purchase cost of the units sold − returned
     */
    public record ProfitParts(long lines, long linesWithoutCost, double revenue, double coveredRevenue, double cost) {
    }
}
