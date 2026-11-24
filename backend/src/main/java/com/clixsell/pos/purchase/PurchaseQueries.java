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
     * product id → recent orders that may already bring it (D22 / D24, P3-D10). All types end
     * for an order once the manager records that the supplier accepted its cancellation.
     * <ul>
     *   <li>RECENTLY_CLOSED: CLOSED in the last 14 days (receiving is not recorded in ClixSell).</li>
     *   <li>CANCELED_AFTER_CONTACT: CANCELED after the manager had recorded sending (last 30 days).</li>
     *   <li>CONTACT_UNKNOWN: CANCELED from MESSAGE_GENERATED with the answer UNKNOWN — the
     *       supplier may have received the message (last 30 days).</li>
     *   <li>DECLARED_NOT_SENT: CANCELED from MESSAGE_GENERATED with the answer NOT_SENT although
     *       the message had been copied or WhatsApp opening requested (last 30 days).</li>
     * </ul>
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
                UNION ALL
                SELECT i.product_id, o.order_id, o.order_number,
                       CASE json_extract(c.details, '$.contactStatus') WHEN 'UNKNOWN' THEN 'CONTACT_UNKNOWN'
                            ELSE 'DECLARED_NOT_SENT' END, o.canceled_at, i.quantity
                FROM purchase_order_items i JOIN purchase_orders o ON o.order_id = i.order_id AND o.store_id = i.store_id
                JOIN purchase_order_events c ON c.order_id = o.order_id AND c.store_id = o.store_id AND c.event_type = 'CANCELED'
                WHERE o.store_id = ? AND o.status = 'CANCELED' AND o.canceled_after_contact = 0 AND o.canceled_at >= ?
                  AND i.product_id IS NOT NULL AND json_valid(c.details)
                  AND (json_extract(c.details, '$.contactStatus') = 'UNKNOWN'
                       OR (json_extract(c.details, '$.contactStatus') = 'NOT_SENT'
                           AND coalesce(json_extract(c.details, '$.priorCopyReports'), 0)
                             + coalesce(json_extract(c.details, '$.priorOpenRequests'), 0) > 0))
                  AND NOT EXISTS (SELECT 1 FROM purchase_order_events e WHERE e.order_id = o.order_id
                                  AND e.event_type = 'SUPPLIER_CANCELLATION_RECORDED')
                ORDER BY 2""",
                rs -> {
                    out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Caution(rs.getLong(1),
                            rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)));
                },
                storeId, PurchaseTime.text(now.minusDays(14)), storeId, PurchaseTime.text(now.minusDays(30)),
                storeId, PurchaseTime.text(now.minusDays(30)));
        return out;
    }

    /**
     * Takes SQLite's write lock for the current transaction WITHOUT changing anything: an UPDATE
     * that matches no row (no row changes, no trigger runs, no timestamp moves). Must be the
     * FIRST statement of the transaction: another writer then waits (busy_timeout) or gets
     * SQLITE_BUSY (the service retries), so "check, then add an event" cannot interleave.
     */
    public void takeWriteLock() {
        db.update("UPDATE stores SET store_id = store_id WHERE 0");
    }

    /** One event of a client action (copy / WhatsApp opening) found by its clientActionId. */
    public record ClientAction(long eventId, long orderId, String eventType, String phase, String result) {
    }

    public List<ClientAction> clientActions(long storeId, String clientActionId) {
        return db.query("""
                SELECT event_id, order_id, event_type, json_extract(details, '$.phase'), json_extract(details, '$.result')
                FROM purchase_order_events
                WHERE store_id = ? AND event_type IN ('MESSAGE_COPIED', 'WHATSAPP_OPEN_REQUESTED') AND json_valid(details)
                  AND json_extract(details, '$.clientActionId') = ?
                ORDER BY event_id""",
                (rs, i) -> new ClientAction(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), storeId, clientActionId);
    }

    /** Number of events of a type (and phase, when given) of one order. */
    public int countEvents(long storeId, long orderId, String type, String phase) {
        Integer n = phase == null
                ? db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE store_id = ? AND order_id = ? "
                        + "AND event_type = ?", Integer.class, storeId, orderId, type)
                : db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE store_id = ? AND order_id = ? "
                        + "AND event_type = ? AND json_valid(details) AND json_extract(details, '$.phase') = ?",
                        Integer.class, storeId, orderId, type, phase);
        return n == null ? 0 : n;
    }

    /** The details (JSON) of the order's CANCELED event, or null. */
    public String cancelDetails(long storeId, long orderId) {
        List<String> l = db.queryForList("SELECT details FROM purchase_order_events WHERE store_id = ? AND order_id = ? "
                + "AND event_type = 'CANCELED' ORDER BY event_id DESC LIMIT 1", String.class, storeId, orderId);
        return l.isEmpty() ? null : l.get(0);
    }

    /** The store's current name (the only live part of a supplier message). */
    public String storeName(long storeId) {
        return db.queryForObject("SELECT name FROM stores WHERE store_id = ?", String.class, storeId);
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
