package com.clixsell.pos.purchase;

import com.clixsell.pos.guardian.GuardianData.ProductFact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read queries for purchasing, always limited to ONE store, plus the store's order-number
 * counter. Products are joined to their supplier only inside the same store.
 */
@Repository
public class PurchaseQueries {

    /** A product now, with its supplier (cost null when not recorded). */
    public record ProductRow(ProductFact fact, String barcode, long supplierId, String supplierName,
                             boolean supplierActive, boolean supplierHasWhatsapp) {
        public long id() { return fact.id(); }
    }

    /** A product held by an open order (open_product_id). */
    public record Reservation(long productId, long orderId, String orderNumber, String status, int quantity) {
    }

    /** A recent order that may already bring this product (D22 / D24). */
    public record Caution(long productId, long orderId, String orderNumber, String type, String at, int quantity) {
    }

    private static final String PRODUCT_SELECT = """
            SELECT p.product_id, p.name, p.category, p.selling_price, p.cost_price, p.quantity, p.min_stock,
                   p.created_at, p.barcode, s.supplier_id, s.name, s.active, s.whatsapp_number
            FROM products p JOIN suppliers s ON s.supplier_id = p.supplier_id AND s.store_id = p.store_id
            WHERE p.store_id = ?""";

    private final JdbcTemplate db;

    public PurchaseQueries(JdbcTemplate db) {
        this.db = db;
    }

    private static ProductRow product(ResultSet rs) throws SQLException {
        double cost = rs.getDouble(5);
        Double costOrNull = rs.wasNull() ? null : cost;
        ProductFact fact = new ProductFact(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDouble(4), costOrNull,
                rs.getInt(6), rs.getInt(7), LocalDateTime.parse(rs.getString(8)));
        String whatsapp = rs.getString(13);
        return new ProductRow(fact, rs.getString(9), rs.getLong(10), rs.getString(11), rs.getInt(12) == 1,
                whatsapp != null && !whatsapp.isBlank());
    }

    public List<ProductRow> products(long storeId) {
        return db.query(PRODUCT_SELECT + " ORDER BY p.name COLLATE NOCASE, p.product_id", (rs, i) -> product(rs), storeId);
    }

    public List<ProductRow> productsOfSupplier(long storeId, long supplierId) {
        return db.query(PRODUCT_SELECT + " AND s.supplier_id = ? ORDER BY p.name COLLATE NOCASE, p.product_id",
                (rs, i) -> product(rs), storeId, supplierId);
    }

    public Optional<ProductRow> product(long storeId, long productId) {
        return db.query(PRODUCT_SELECT + " AND p.product_id = ?", (rs, i) -> product(rs), storeId, productId)
                .stream().findFirst();
    }

    /** product id → the open order that holds it. */
    public Map<Long, Reservation> reservations(long storeId) {
        Map<Long, Reservation> out = new HashMap<>();
        db.query("""
                SELECT i.open_product_id, o.order_id, o.order_number, o.status, i.quantity
                FROM purchase_order_items i JOIN purchase_orders o ON o.order_id = i.order_id AND o.store_id = i.store_id
                WHERE i.store_id = ? AND i.open_product_id IS NOT NULL""",
                rs -> {
                    out.put(rs.getLong(1), new Reservation(rs.getLong(1), rs.getLong(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5)));
                }, storeId);
        return out;
    }

    /**
     * product id → recent orders that may already bring it:
     * CLOSED in the last 14 days (receiving not recorded), or CANCELED after supplier contact in
     * the last 30 days without a recorded supplier acceptance of the cancellation.
     */
    public Map<Long, List<Caution>> cautions(long storeId, LocalDateTime now) {
        Map<Long, List<Caution>> out = new HashMap<>();
        db.query("""
                SELECT i.product_id, o.order_id, o.order_number, 'RECENTLY_CLOSED', o.closed_at, i.quantity
                FROM purchase_order_items i JOIN purchase_orders o ON o.order_id = i.order_id AND o.store_id = i.store_id
                WHERE o.store_id = ? AND o.status = 'CLOSED' AND o.closed_at >= ? AND i.product_id IS NOT NULL
                UNION ALL
                SELECT i.product_id, o.order_id, o.order_number, 'CANCELED_AFTER_CONTACT', o.canceled_at, i.quantity
                FROM purchase_order_items i JOIN purchase_orders o ON o.order_id = i.order_id AND o.store_id = i.store_id
                WHERE o.store_id = ? AND o.status = 'CANCELED' AND o.canceled_after_contact = 1 AND o.canceled_at >= ?
                  AND i.product_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM purchase_order_events e WHERE e.order_id = o.order_id
                                  AND e.event_type = 'SUPPLIER_CANCELLATION_RECORDED')
                ORDER BY 2""",
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Caution(rs.getLong(1),
                            rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)));
                },
                storeId, PurchaseTime.text(now.minusDays(14)), storeId, PurchaseTime.text(now.minusDays(30)));
        return out;
    }

    /**
     * Takes the store's next order number in ONE atomic statement (inside the caller's
     * transaction; rolled back with it). UNIQUE (store_id, order_number) guards it as well.
     */
    public long takeOrderNumber(long storeId) {
        int updated = db.update("UPDATE stores SET next_purchase_order_number = next_purchase_order_number + 1 "
                + "WHERE store_id = ?", storeId);
        if (updated != 1) throw new IllegalStateException("Store " + storeId + " not found");
        Long next = db.queryForObject("SELECT next_purchase_order_number FROM stores WHERE store_id = ?", Long.class,
                storeId);
        return next - 1;
    }
}
