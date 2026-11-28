package com.clixsell.pos.purchase;

import com.clixsell.pos.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared data for the purchasing API tests: two stores ("Shini" and "Neshan") with
 * overlapping supplier and product names but different data, a second Shini Store Admin,
 * helpers to create suppliers / products through the real API, and helpers that write past
 * sales, returns and historic orders into the TEST database (the history the rules read).
 */
public abstract class PurchaseApiTestBase extends ApiTestBase {

    protected static final DateTimeFormatter T = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    protected StoreSetup shini;
    protected StoreSetup neshan;
    protected String shiniAdmin2;
    private final AtomicInteger invoice = new AtomicInteger(100);
    private final AtomicInteger historic = new AtomicInteger(900000);

    @BeforeEach
    void twoStores() throws Exception {
        shini = setupStore("Shini", "shini");
        neshan = setupStore("Neshan", "neshan");
        createStoreAdmin(sysadminToken(), shini.storeId(), "shini.admin2", "Shop-Owner-Pass-2");
        shiniAdmin2 = login("shini.admin2", "Shop-Owner-Pass-2");
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────────

    protected MvcResult call(String method, String url, String token, String body) throws Exception {
        var req = switch (method) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            case "PUT" -> put(url);
            default -> throw new IllegalArgumentException(method);
        };
        if (token != null) req = req.header("Authorization", bearer(token));
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn();
    }

    protected static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    @SuppressWarnings("unchecked")
    protected static <T> T read(MvcResult r, String path) throws Exception {
        return (T) JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    protected static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    // ── data through the real API ───────────────────────────────────────────────────

    protected long supplier(String token, String name, String whatsapp) throws Exception {
        MvcResult r = call("POST", "/api/suppliers", token, whatsapp == null ? "{\"name\":\"%s\"}".formatted(name)
                : "{\"name\":\"%s\",\"whatsappNumber\":\"%s\"}".formatted(name, whatsapp));
        if (status(r) != 201) throw new IllegalStateException(body(r));
        return ((Number) read(r, "$.supplierId")).longValue();
    }

    /** A product created {@code ageDays} days ago (so the sales history can be observed). */
    protected long product(String token, String name, String barcode, int qty, int min, String supplier, Double cost,
                           int ageDays) throws Exception {
        String json = """
                {"name":"%s","barcode":"%s","category":"dairy","sellingPrice":14,"quantity":%d,"minStock":%d,
                 "supplierName":"%s"%s}""".formatted(name, barcode, qty, min, supplier,
                cost == null ? "" : ",\"costPrice\":" + cost);
        MvcResult r = call("POST", "/api/products", token, json);
        if (status(r) != 201) throw new IllegalStateException(body(r));
        long id = ((Number) read(r, "$.productId")).longValue();
        db.update("UPDATE products SET created_at = ? WHERE product_id = ?",
                T.format(LocalDateTime.now().minusDays(ageDays).withNano(0)), id);
        return id;
    }

    protected void guardian(String token, boolean on) throws Exception {
        MvcResult r = call("PUT", "/api/guardian/status", token, "{\"enabled\":" + on + "}");
        if (status(r) != 200) throw new IllegalStateException(body(r));
    }

    // ── history written into the TEST database ──────────────────────────────────────

    private long cashierOf(long storeId) {
        return db.queryForObject("SELECT user_id FROM users WHERE store_id = ? AND role = 'CASHIER' LIMIT 1", Long.class,
                storeId);
    }

    protected long adminId(String username) {
        return db.queryForObject("SELECT user_id FROM users WHERE username = ?", Long.class, username);
    }

    /** A completed sale of {@code units} units, {@code daysAgo} days ago. Returns the sale item id. */
    protected long sold(long storeId, long productId, int units, int daysAgo) {
        String at = T.format(LocalDateTime.now().minusDays(daysAgo).withNano(0));
        long cashier = cashierOf(storeId);
        db.update("""
                INSERT INTO sales (store_id, cashier_id, invoice_number, status, subtotal, discount, tax_rate, tax, total,
                                   created_at, updated_at, completed_at)
                VALUES (?, ?, ?, 'COMPLETED', ?, 0, 0, 0, ?, ?, ?, ?)""",
                storeId, cashier, "T-" + invoice.incrementAndGet(), units * 14.0, units * 14.0, at, at, at);
        long saleId = db.queryForObject("SELECT MAX(sale_id) FROM sales", Long.class);
        // as the application does: every completed sale has its payment (V7 checks refunds against it)
        db.update("INSERT INTO payments (sale_id, payment_method, amount, payment_status, paid_at) VALUES (?, 'CASH', ?, 'PAID', ?)",
                saleId, units * 14.0, at);
        db.update("""
                INSERT INTO sale_items (sale_id, product_id, product_name, barcode, quantity, unit_price, line_total,
                                        returned_quantity, unit_cost)
                SELECT ?, product_id, name, barcode, ?, 14, ?, 0, cost_price FROM products WHERE product_id = ?""",
                saleId, units, units * 14.0, productId);
        return db.queryForObject("SELECT MAX(sale_item_id) FROM sale_items", Long.class);
    }

    /** A return of {@code units} units of an earlier sale line, {@code daysAgo} days ago. */
    protected void returned(long storeId, long productId, long saleItemId, int units, int daysAgo) {
        String at = T.format(LocalDateTime.now().minusDays(daysAgo).withNano(0));
        long saleId = db.queryForObject("SELECT sale_id FROM sale_items WHERE sale_item_id = ?", Long.class, saleItemId);
        db.update("""
                INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason,
                                     refund_amount, refund_method, return_date)
                VALUES (?, ?, ?, ?, ?, ?, 'Test', ?, 'CASH', ?)""",
                storeId, saleId, saleItemId, productId, cashierOf(storeId), units, units * 14.0, at);
        db.update("UPDATE sale_items SET returned_quantity = returned_quantity + ? WHERE sale_item_id = ?", units, saleItemId);
    }

    /**
     * A historic order (as Phase 3 will create them) written step by step through every state,
     * so the database rules are respected: CLOSED, or CANCELED after supplier contact, at the
     * given time. Returns the order id.
     */
    protected long historicOrder(long storeId, long supplierId, String adminUsername, long productId, int qty,
                                 String finalState, int daysAgo) {
        long admin = adminId(adminUsername);
        String at = T.format(LocalDateTime.now().minusDays(daysAgo).withNano(0));
        String number = "PO-" + historic.incrementAndGet();
        db.update("""
                INSERT INTO purchase_orders (store_id, order_number, supplier_id, supplier_name, status, source, created_by,
                                             created_at, client_request_id, request_hash)
                VALUES (?, ?, ?, 'Historic', 'PENDING_APPROVAL', 'MANUAL', ?, ?, ?, ?)""",
                storeId, number, supplierId, admin, at, UUID.randomUUID().toString(), "a".repeat(64));
        long id = db.queryForObject("SELECT order_id FROM purchase_orders WHERE order_number = ? AND store_id = ?",
                Long.class, number, storeId);
        db.update("""
                INSERT INTO purchase_order_items (order_id, store_id, product_id, product_store_id, product_name, barcode,
                                                  quantity, basis, calc_json, open_product_id)
                SELECT ?, ?, product_id, store_id, name, barcode, ?, 'MANUAL', '{}', product_id FROM products WHERE product_id = ?""",
                id, storeId, qty, productId);
        step(id, "status='APPROVED', approved_by=" + admin + ", approved_at='" + at + "'");
        step(id, "status='MESSAGE_GENERATED', message_text='x', message_language='ar', message_whatsapp='970599000001', "
                + "message_generated_by=" + admin + ", message_generated_at='" + at + "'");
        step(id, "status='SENT', sent_marked_by=" + admin + ", sent_marked_at='" + at + "'");
        if (finalState.equals("CLOSED")) {
            step(id, "status='CONFIRMED', confirmation_recorded_by=" + admin + ", confirmation_recorded_at='" + at + "'");
            step(id, "status='CLOSED', closed_by=" + admin + ", closed_at='" + at + "'");
        } else {
            step(id, "status='CANCELED', canceled_by=" + admin + ", canceled_at='" + at + "', cancel_reason='Too slow', "
                    + "canceled_after_contact=1");
        }
        return id;
    }

    private void step(long id, String set) {
        db.update("UPDATE purchase_orders SET " + set + ", version = version + 1 WHERE order_id = ?", id);
    }

    // ── purchase helpers ────────────────────────────────────────────────────────────

    protected static String orderJson(String key, long supplierId, String source, String note, List<long[]> items,
                                      List<long[]> acks) {
        StringBuilder b = new StringBuilder("{\"clientRequestId\":\"").append(key).append("\",\"supplierId\":")
                .append(supplierId).append(",\"source\":\"").append(source).append('"');
        if (note != null) b.append(",\"note\":\"").append(note).append('"');
        b.append(",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append("{\"productId\":").append(items.get(i)[0]).append(",\"quantity\":").append(items.get(i)[1]).append('}');
        }
        b.append(']');
        if (acks != null) {
            b.append(",\"acknowledgedCautions\":[");
            for (int i = 0; i < acks.size(); i++) {
                if (i > 0) b.append(',');
                b.append("{\"productId\":").append(acks.get(i)[0]).append(",\"orderId\":").append(acks.get(i)[1]).append('}');
            }
            b.append(']');
        }
        return b.append('}').toString();
    }

    protected static List<long[]> items(long... pairs) {
        List<long[]> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) out.add(new long[]{pairs[i], pairs[i + 1]});
        return out;
    }

    protected MvcResult create(String token, long supplierId, String source, long... pairs) throws Exception {
        return call("POST", "/api/purchase-orders", token,
                orderJson(UUID.randomUUID().toString(), supplierId, source, null, items(pairs), null));
    }

    protected long createOk(String token, long supplierId, String source, long... pairs) throws Exception {
        MvcResult r = create(token, supplierId, source, pairs);
        if (status(r) != 201) throw new IllegalStateException(body(r));
        return ((Number) read(r, "$.orderId")).longValue();
    }

    protected int version(long orderId) {
        return db.queryForObject("SELECT version FROM purchase_orders WHERE order_id = ?", Integer.class, orderId);
    }

    protected String state(long orderId) {
        return db.queryForObject("SELECT status FROM purchase_orders WHERE order_id = ?", String.class, orderId);
    }

    protected long events(long orderId) {
        return db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE order_id = ?", Long.class, orderId);
    }

    protected List<String> eventTypes(long orderId) {
        return db.queryForList("SELECT event_type FROM purchase_order_events WHERE order_id = ? ORDER BY event_id",
                String.class, orderId);
    }

    protected Long reservedBy(long productId) {
        List<Long> l = db.queryForList("SELECT order_id FROM purchase_order_items WHERE open_product_id = ?", Long.class,
                productId);
        return l.isEmpty() ? null : l.get(0);
    }

    protected long counter(long storeId) {
        return db.queryForObject("SELECT next_purchase_order_number FROM stores WHERE store_id = ?", Long.class, storeId);
    }

    /** Open order ⇒ every line reserved; final order ⇒ none; every row in its own store. */
    protected void assertDatabaseConsistent() {
        long bad = db.queryForObject("""
                SELECT COUNT(*) FROM purchase_order_items i JOIN purchase_orders o ON o.order_id = i.order_id
                WHERE (o.status IN ('REJECTED','CANCELED','CLOSED') AND i.open_product_id IS NOT NULL)
                   OR (o.status NOT IN ('REJECTED','CANCELED','CLOSED') AND i.open_product_id IS NULL AND i.product_id IS NOT NULL)
                   OR i.store_id <> o.store_id""", Long.class);
        if (bad != 0) throw new AssertionError(bad + " purchase-order line(s) break the reservation / store rules");
        long fk = db.queryForList("PRAGMA foreign_key_check").size();
        if (fk != 0) throw new AssertionError("foreign_key_check found " + fk + " problem(s)");
        long crossEvents = db.queryForObject("""
                SELECT COUNT(*) FROM purchase_order_events e JOIN purchase_orders o ON o.order_id = e.order_id
                WHERE e.store_id <> o.store_id""", Long.class);
        if (crossEvents != 0) throw new AssertionError("events in the wrong store");
    }

    /** Everything purchasing must never change: products, sales, returns, totals. */
    protected Map<String, Object> financialSnapshot() {
        return Map.of(
                "products", db.queryForList("SELECT product_id, quantity, selling_price, cost_price, supplier_id FROM products "
                        + "ORDER BY product_id"),
                "sales", db.queryForList("SELECT sale_id, status, total, completed_at FROM sales ORDER BY sale_id"),
                "saleItems", db.queryForList("SELECT sale_item_id, quantity, unit_cost, returned_quantity FROM sale_items "
                        + "ORDER BY sale_item_id"),
                "returns", db.queryForList("SELECT return_id, quantity, refund_amount FROM returns ORDER BY return_id"));
    }
}
