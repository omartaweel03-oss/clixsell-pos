package com.clixsell.pos.purchase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1 (final corrections): GET /api/purchase-orders/summary gives EXACT counts by status and the
 * exact overdue count of the logged-in Store Admin's store, computed by the database in one
 * statement (no paging, no 2,000 limit, nothing counted twice), and ?overdue=true lists exactly
 * those overdue orders over all pages. The database's overdue rule is checked against the Java
 * rule (PurchaseOrderService.overdue) order by order, including orders at the boundaries.
 */
class PurchaseSummaryApiTest extends PurchaseApiTestBase {

    private static final String S = "/api/purchase-orders/summary";
    private static final List<String> OPEN = List.of("PENDING_APPROVAL", "APPROVED", "MESSAGE_GENERATED", "SENT", "CONFIRMED");

    @Autowired
    PurchaseOrderRepository repository;

    /**
     * Adds {@code n} products (copies of {@code template} with new names / barcodes) and one
     * PENDING_APPROVAL order with one reserved item for each, all in single SQL statements that
     * pass every V6 trigger. Order i gets the number PO-&lt;prefix&gt;&lt;i 6 digits&gt;.
     */
    private void bulkOrders(long storeId, long template, String adminUsername, int prefix, int n, String createdAt) {
        List<String> cols = new ArrayList<>(db.queryForList("SELECT name FROM pragma_table_info('products')", String.class));
        cols.remove("product_id");
        String select = String.join(", ", cols.stream().map(c -> switch (c) {
            case "name" -> "'Bulk ' || n.i";
            case "barcode" -> "printf('%d%07d', " + prefix + ", n.i)";
            default -> "p." + c;
        }).toList());
        db.update("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < ?) "
                + "INSERT INTO products (" + String.join(", ", cols) + ") SELECT " + select
                + " FROM products p, n WHERE p.product_id = ?", n, template);
        long admin = adminId(adminUsername);
        db.update("""
                INSERT INTO purchase_orders (store_id, order_number, supplier_id, supplier_name, status, source, created_by,
                                             created_at, client_request_id, request_hash)
                SELECT p.store_id, 'PO-' || substr(p.barcode, 1), p.supplier_id, 'Bulk Supplier', 'PENDING_APPROVAL', 'MANUAL', ?,
                       ?, printf('%08d-0000-4000-8000-%012d', ?, p.product_id), ?
                FROM products p WHERE p.store_id = ? AND p.barcode GLOB ? """,
                admin, createdAt, prefix, "b".repeat(64), storeId, prefix + "[0-9][0-9][0-9][0-9][0-9][0-9][0-9]");
        db.update("""
                INSERT INTO purchase_order_items (order_id, store_id, product_id, product_store_id, product_name, barcode,
                                                  quantity, basis, calc_json, open_product_id)
                SELECT o.order_id, o.store_id, p.product_id, p.store_id, p.name, p.barcode, 1, 'MANUAL', '{}', p.product_id
                FROM purchase_orders o JOIN products p ON p.store_id = o.store_id AND o.order_number = 'PO-' || p.barcode
                WHERE o.store_id = ? AND p.barcode GLOB ?""", storeId, prefix + "[0-9][0-9][0-9][0-9][0-9][0-9][0-9]");
    }

    /** Moves bulk orders whose number (last 7 digits) modulo 5 equals {@code mod} up to the given state. */
    private void advance(long storeId, String adminUsername, int prefix, int mod, String target, String at) {
        long admin = adminId(adminUsername);
        String where = " WHERE store_id = " + storeId + " AND order_number GLOB 'PO-" + prefix
                + "[0-9][0-9][0-9][0-9][0-9][0-9][0-9]' AND CAST(substr(order_number, 5) AS INTEGER) % 5 = " + mod;
        List<String> steps = new ArrayList<>();
        steps.add("status='APPROVED', approved_by=" + admin + ", approved_at='" + at + "'");
        steps.add("status='MESSAGE_GENERATED', message_text='x', message_language='ar', message_whatsapp='970599000001', "
                + "message_generated_by=" + admin + ", message_generated_at='" + at + "'");
        steps.add("status='SENT', sent_marked_by=" + admin + ", sent_marked_at='" + at + "'");
        steps.add("status='CONFIRMED', confirmation_recorded_by=" + admin + ", confirmation_recorded_at='" + at + "'");
        int upTo = OPEN.indexOf(target);
        for (int s = 0; s < upTo; s++) db.update("UPDATE purchase_orders SET " + steps.get(s) + ", version = version + 1" + where);
    }

    private Map<String, Long> dbCounts(long storeId) {
        Map<String, Long> m = new HashMap<>();
        for (PurchaseOrderStatus s : PurchaseOrderStatus.values()) m.put(s.name(), 0L);
        db.query("SELECT status, COUNT(*) FROM purchase_orders WHERE store_id = ? GROUP BY status",
                rs -> {
                    m.put(rs.getString(1), rs.getLong(2));
                }, storeId);
        return m;
    }

    /** The overdue orders by the JAVA rule, read entity by entity. */
    private Set<Long> javaOverdue(long storeId, LocalDateTime now) {
        Set<Long> out = new HashSet<>();
        for (PurchaseOrder o : repository.findAll()) {
            if (o.getStoreId() == storeId && PurchaseOrderService.overdue(o, now)) out.add(o.getOrderId());
        }
        return out;
    }

    private static long num(MvcResult r, String path) throws Exception {
        return ((Number) read(r, path)).longValue();
    }

    @Test
    void summaryIsExactAboveTwoThousandOpenOrdersAndOverdueMatchesTheRule() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long template = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String old = T.format(now.minusDays(20));
        String fresh = T.format(now.minusMinutes(5));
        // 2,150 fresh + 400 old (overdue) orders spread over the five open states
        bulkOrders(shini.storeId(), template, "shini.admin", 5, 2150, fresh);
        bulkOrders(shini.storeId(), template, "shini.admin", 6, 400, old);
        for (int mod = 1; mod <= 4; mod++) {
            advance(shini.storeId(), "shini.admin", 5, mod, OPEN.get(mod), fresh);
            advance(shini.storeId(), "shini.admin", 6, mod, OPEN.get(mod), old);
        }
        // boundaries: pending exactly 3 days − 2 minutes (not overdue) and 3 days + 2 minutes (overdue);
        // approved 1 day ± 2 minutes; sent 3 days ± 2 minutes; confirmed 14 days ± 2 minutes
        String[][] edges = {
                {"PENDING_APPROVAL", T.format(now.minusDays(3).plusMinutes(2)), T.format(now.minusDays(3).minusMinutes(2))},
                {"APPROVED", T.format(now.minusDays(1).plusMinutes(2)), T.format(now.minusDays(1).minusMinutes(2))},
                {"MESSAGE_GENERATED", T.format(now.minusDays(1).plusMinutes(2)), T.format(now.minusDays(1).minusMinutes(2))},
                {"SENT", T.format(now.minusDays(3).plusMinutes(2)), T.format(now.minusDays(3).minusMinutes(2))},
                {"CONFIRMED", T.format(now.minusDays(14).plusMinutes(2)), T.format(now.minusDays(14).minusMinutes(2))}};
        int prefix = 7;
        for (String[] e : edges) {
            for (int k = 1; k <= 2; k++) {
                bulkOrders(shini.storeId(), template, "shini.admin", prefix, 5, k == 1 ? e[1] : e[2]);
                for (int mod = 0; mod < 5; mod++) advance(shini.storeId(), "shini.admin", prefix, mod, e[0], k == 1 ? e[1] : e[2]);
                prefix++;
            }
        }
        // final orders through the API (never counted as open or overdue)
        long a = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long b = product(shini.adminToken(), "Yogurt", "33333333", 3, 5, "Dairy Co", 2.0, 30);
        long ra = createOk(shini.adminToken(), dairy, "MANUAL", a, 1);
        long cb = createOk(shini.adminToken(), dairy, "MANUAL", b, 1);
        assertThat(status(call("POST", "/api/purchase-orders/" + ra + "/reject", shini.adminToken(),
                "{\"version\":0,\"reason\":\"Not needed\"}"))).isEqualTo(200);
        assertThat(status(call("POST", "/api/purchase-orders/" + cb + "/cancel", shini.adminToken(),
                "{\"version\":0,\"reason\":\"Not needed\"}"))).isEqualTo(200);
        historicOrder(shini.storeId(), dairy, "shini.admin", template, 1, "CLOSED", 2);
        // the other store has its own orders with the same numbers
        long nd = supplier(neshan.adminToken(), "Dairy Co", "970599000009");
        long nt = product(neshan.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        bulkOrders(neshan.storeId(), nt, "neshan.admin", 5, 37, old);
        assertDatabaseConsistent();

        MvcResult r = call("GET", S, shini.adminToken(), null);
        assertThat(status(r)).isEqualTo(200);
        Map<String, Long> expected = dbCounts(shini.storeId());
        long open = OPEN.stream().mapToLong(expected::get).sum();
        assertThat(open).as("more than 2,000 open orders").isGreaterThan(2500);
        for (PurchaseOrderStatus st : PurchaseOrderStatus.values()) {
            assertThat(num(r, "$.byStatus." + st.name())).as(st.name()).isEqualTo(expected.get(st.name()));
        }
        assertThat(num(r, "$.open")).isEqualTo(open);
        assertThat(num(r, "$.byStatus.REJECTED")).isEqualTo(1);
        assertThat(num(r, "$.byStatus.CANCELED")).isEqualTo(1);
        assertThat(num(r, "$.byStatus.CLOSED")).isEqualTo(1);

        Set<Long> javaLate = javaOverdue(shini.storeId(), LocalDateTime.now().withNano(0));
        // 400 old + one "just past the boundary" group of 5 per state
        assertThat(javaLate).hasSize(400 + 25);
        assertThat(num(r, "$.overdue")).isEqualTo(javaLate.size());
        long perState = 0;
        for (String st : OPEN) perState += num(r, "$.overdueByStatus." + st);
        assertThat(perState).isEqualTo(javaLate.size());
        assertThat(num(r, "$.overdueByStatus.PENDING_APPROVAL")).isEqualTo(80 + 5);

        // ?overdue=true: every overdue order exactly once over all pages, newest first
        List<Long> listed = new ArrayList<>();
        long total = -1;
        for (int p = 0; ; p++) {
            MvcResult page = call("GET", "/api/purchase-orders?overdue=true&size=100&page=" + p, shini.adminToken(), null);
            assertThat(status(page)).isEqualTo(200);
            total = num(page, "$.total");
            List<Integer> ids = read(page, "$.orders[*].orderId");
            List<Boolean> flags = read(page, "$.orders[*].overdue");
            assertThat(flags).as("every listed order is overdue by the Java rule as well").doesNotContain(false);
            if (ids.isEmpty()) break;
            ids.forEach(i -> listed.add(i.longValue()));
        }
        assertThat(total).isEqualTo(javaLate.size());
        assertThat(listed).doesNotHaveDuplicates().hasSize(javaLate.size());
        assertThat(new HashSet<>(listed)).isEqualTo(javaLate);
        List<Long> sorted = new ArrayList<>(listed);
        sorted.sort(Collections.reverseOrder());
        assertThat(listed).isEqualTo(sorted);
        // combined with a status filter and with the search
        assertThat(num(call("GET", "/api/purchase-orders?overdue=true&status=SENT&size=5", shini.adminToken(), null), "$.total"))
                .isEqualTo(80 + 5);
        String one = db.queryForObject("SELECT order_number FROM purchase_orders WHERE store_id = ? AND order_number = 'PO-60000010'",
                String.class, shini.storeId());
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/purchase-orders")
                .param("overdue", "true").param("q", one).header("Authorization", bearer(shini.adminToken()));
        assertThat(num(mvc.perform(req).andReturn(), "$.total")).isEqualTo(1);
        assertThat(status(call("GET", "/api/purchase-orders?overdue=maybe", shini.adminToken(), null))).isEqualTo(400);
        assertThat(num(call("GET", "/api/purchase-orders?overdue=false&size=1", shini.adminToken(), null), "$.total"))
                .isEqualTo(expected.values().stream().mapToLong(Long::longValue).sum());

        // isolation: Neshan sees only its own 37 (old → overdue) orders
        MvcResult n = call("GET", S, neshan.adminToken(), null);
        assertThat(num(n, "$.open")).isEqualTo(37);
        assertThat(num(n, "$.overdue")).isEqualTo(37);
        assertThat(num(n, "$.byStatus.REJECTED")).isZero();
        assertThat(num(call("GET", "/api/purchase-orders?overdue=true", neshan.adminToken(), null), "$.total")).isEqualTo(37);
        assertThat(nd).isPositive();

        // roles: Store Admin only
        assertThat(status(call("GET", S, shini.cashierToken(), null))).isEqualTo(403);
        assertThat(status(call("GET", S, sysadminToken(), null))).isEqualTo(403);
        assertThat(status(call("GET", S, null, null))).isEqualTo(401);
        assertThat(status(call("GET", "/api/purchase-orders?overdue=true", shini.cashierToken(), null))).isEqualTo(403);
        // reading changed nothing
        assertThat(dbCounts(shini.storeId())).isEqualTo(expected);
    }

    @Test
    void summaryDuringConcurrentCreationNeverCountsTwiceOrLoses() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        int creators = 6;
        int perCreator = 6;
        List<Long> products = new ArrayList<>();
        for (int i = 0; i < creators * perCreator; i++) {
            products.add(product(shini.adminToken(), "P" + i, String.format("5%07d", i), 3, 5, "Dairy Co", 1.0, 30));
        }
        long before = num(call("GET", S, shini.adminToken(), null), "$.byStatus.PENDING_APPROVAL");
        assertThat(before).isZero();

        ExecutorService pool = Executors.newFixedThreadPool(creators + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean(false);
        List<Future<Integer>> made = new ArrayList<>();
        for (int c = 0; c < creators; c++) {
            int first = c * perCreator;
            made.add(pool.submit(() -> {
                start.await();
                int ok = 0;
                for (int k = 0; k < perCreator; k++) {
                    MvcResult r = call("POST", "/api/purchase-orders", shini.adminToken(), orderJson(UUID.randomUUID().toString(),
                            dairy, "MANUAL", null, items(products.get(first + k), 1), null));
                    if (status(r) == 201) ok++;
                }
                return ok;
            }));
        }
        Future<List<long[]>> reader = pool.submit(() -> {
            start.await();
            List<long[]> seen = new ArrayList<>();
            while (!done.get()) {
                MvcResult r = call("GET", S, shini.adminToken(), null);
                if (status(r) == 200) {
                    seen.add(new long[]{num(r, "$.byStatus.PENDING_APPROVAL"), num(r, "$.open"), num(r, "$.overdue")});
                }
            }
            return seen;
        });
        start.countDown();
        int created = 0;
        for (Future<Integer> f : made) created += f.get(120, TimeUnit.SECONDS);
        done.set(true);
        List<long[]> seen = reader.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(created).isEqualTo(creators * perCreator);
        assertThat(seen).isNotEmpty();
        long last = 0;
        for (long[] s : seen) {
            assertThat(s[0]).as("never more than were created").isBetween(0L, (long) created);
            assertThat(s[1]).as("open = pending here").isEqualTo(s[0]);
            assertThat(s[2]).as("new orders are not overdue").isZero();
            assertThat(s[0]).as("counts only grow while orders are being created").isGreaterThanOrEqualTo(last);
            last = s[0];
        }
        MvcResult end = call("GET", S, shini.adminToken(), null);
        assertThat(num(end, "$.byStatus.PENDING_APPROVAL")).isEqualTo(created)
                .isEqualTo(dbCounts(shini.storeId()).get("PENDING_APPROVAL"));
        assertDatabaseConsistent();
    }
}
