package com.clixsell.pos.purchase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real concurrent HTTP requests (threads released together) against the real application
 * with FOUR database connections (production uses one), so correctness comes from the
 * database rules and the service — unique order numbers, one reservation per product,
 * optimistic versions, exactly one event per successful change — and not from requests
 * happening to run one after another. A request that loses a race gets a clean 409
 * (never 500, never half-saved data); retrying with the same clientRequestId is safe.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:test-clixsell.db?foreign_keys=on&busy_timeout=15000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
class PurchaseConcurrencyTest extends PurchaseApiTestBase {

    private static final Set<String> CLEAN_LOSSES = Set.of("CONCURRENT_CHANGE", "DATABASE_BUSY", "PRODUCT_IN_OPEN_ORDER",
            "ORDER_CHANGED", "INVALID_TRANSITION");

    @AfterEach
    void databaseStaysConsistent() {
        assertDatabaseConsistent();
    }

    private record Answer(int status, String code, Long orderId, String number) {
    }

    private Answer answer(MvcResult r) throws Exception {
        int s = status(r);
        if (s == 200 || s == 201) {
            return new Answer(s, null, ((Number) read(r, "$.orderId")).longValue(), read(r, "$.orderNumber"));
        }
        String code = body(r).contains("\"code\"") ? read(r, "$.code") : "HTTP_" + s;
        return new Answer(s, code, null, null);
    }

    /** Runs all calls at the same moment and returns their answers. */
    private List<Answer> together(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> c : calls) {
            futures.add(pool.submit(() -> {
                start.await();
                return c.call();
            }));
        }
        start.countDown();
        List<Answer> out = new ArrayList<>();
        for (Future<MvcResult> f : futures) out.add(answer(f.get(60, TimeUnit.SECONDS)));
        pool.shutdown();
        System.out.println("CONCURRENCY " + out.stream().map(a -> a.status() + (a.code() == null ? "" : ":" + a.code()))
                .collect(java.util.stream.Collectors.groupingBy(x -> x, java.util.TreeMap::new, java.util.stream.Collectors.counting())));
        for (Answer a : out) {
            assertThat(a.status()).as("never a server error: " + a).isIn(200, 201, 409);
            if (a.status() == 409) assertThat(a.code()).as("a clean, documented loss").isIn(CLEAN_LOSSES);
        }
        return out;
    }

    private static long successes(List<Answer> answers) {
        return answers.stream().filter(a -> a.status() == 201 || a.status() == 200).count();
    }

    @Test
    void c1TheSameRequestSentEightTimesAtOnceCreatesOneOrder() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        String json = orderJson(UUID.randomUUID().toString(), dairy, "MANUAL", null, items(labneh, 5), null);
        List<Callable<MvcResult>> calls = Collections.nCopies(8, () -> call("POST", "/api/purchase-orders", shini.adminToken(), json));
        List<Answer> answers = together(calls);
        assertThat(answers.stream().filter(a -> a.status() == 201).count()).isEqualTo(1);
        assertThat(answers.stream().filter(a -> a.orderId() != null).map(Answer::orderId).distinct().count()).isEqualTo(1);
        // the losers retry with the same key → the same order
        MvcResult retry = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        assertThat(status(retry)).isEqualTo(200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_events", Long.class)).isEqualTo(1);
        assertThat(counter(shini.storeId())).isEqualTo(2);
    }

    @Test
    void c3TwentyOrdersAtOnceGetUniqueNumbersWithoutGaps() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long p = product(shini.adminToken(), "Item " + i, String.valueOf(70000000 + i), 3, 5, "Dairy Co", 1.0, 30);
            bodies.add(orderJson(UUID.randomUUID().toString(), dairy, "MANUAL", null, items(p, 1), null));
        }
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (String b : bodies) calls.add(() -> call("POST", "/api/purchase-orders", shini.adminToken(), b));
        List<Answer> answers = together(calls);
        // every request is retried with its own key until it is answered with an order (safe retry)
        for (int i = 0; i < bodies.size(); i++) {
            for (int attempt = 0; attempt < 10 && answers.get(i).orderId() == null; attempt++) {
                answers.set(i, answer(call("POST", "/api/purchase-orders", shini.adminToken(), bodies.get(i))));
            }
        }
        assertThat(answers).allSatisfy(a -> assertThat(a.orderId()).isNotNull());
        List<String> numbers = db.queryForList("SELECT order_number FROM purchase_orders WHERE store_id=? ORDER BY order_number",
                String.class, shini.storeId());
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 20; i++) expected.add("PO-%06d".formatted(i));
        assertThat(numbers).isEqualTo(expected);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE event_type='CREATED'", Long.class))
                .isEqualTo(20);
        assertThat(counter(shini.storeId())).isEqualTo(21);
    }

    @Test
    void c4OneProductRequestedByEightOrdersAtOnceIsReservedExactlyOnce() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String b = orderJson(UUID.randomUUID().toString(), dairy, "MANUAL", null, items(labneh, 5 + i), null);
            calls.add(() -> call("POST", "/api/purchase-orders", shini.adminToken(), b));
            String b2 = orderJson(UUID.randomUUID().toString(), dairy, "MANUAL", null, items(labneh, 9 + i), null);
            calls.add(() -> call("POST", "/api/purchase-orders", shiniAdmin2, b2));
        }
        List<Answer> answers = together(calls);
        assertThat(successes(answers)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE open_product_id=?", Long.class, labneh))
                .isEqualTo(1);
        assertThat(counter(shini.storeId())).as("losers took no number").isEqualTo(2);
    }

    @Test
    void c5ApprovingTheSameVersionSixTimesAtOnceApprovesOnce() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long id = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String token = i % 2 == 0 ? shini.adminToken() : shiniAdmin2;
            calls.add(() -> call("POST", "/api/purchase-orders/" + id + "/approve", token, "{\"version\":0}"));
        }
        List<Answer> answers = together(calls);
        assertThat(successes(answers)).isEqualTo(1);
        assertThat(state(id)).isEqualTo("APPROVED");
        assertThat(version(id)).isEqualTo(1);
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED");
    }

    @Test
    void c6ApproveRejectAndCancelAtOnceEndInExactlyOneOutcome() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long id = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);
        List<Callable<MvcResult>> calls = List.of(
                () -> call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":0}"),
                () -> call("POST", "/api/purchase-orders/" + id + "/reject", shiniAdmin2, "{\"version\":0,\"reason\":\"No need\"}"),
                () -> call("POST", "/api/purchase-orders/" + id + "/cancel", shini.adminToken(), "{\"version\":0,\"reason\":\"Changed\"}"),
                () -> call("PUT", "/api/purchase-orders/" + id, shiniAdmin2,
                        "{\"version\":0,\"items\":[{\"productId\":" + labneh + ",\"quantity\":8}]}"));
        List<Answer> answers = together(calls);
        assertThat(successes(answers)).isEqualTo(1);
        assertThat(version(id)).isEqualTo(1);
        assertThat(events(id)).isEqualTo(2);
        String outcome = state(id);
        Long holder = reservedBy(labneh);
        if (outcome.equals("REJECTED") || outcome.equals("CANCELED")) assertThat(holder).isNull();
        else assertThat(holder).isEqualTo(id);
    }

    @Test
    void c7TwoStoresCreatingAtTheSameTimeNumberTheirOrdersIndependently() throws Exception {
        long sDairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long nDairy = supplier(neshan.adminToken(), "Dairy Co", null);
        List<String[]> work = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long sp = product(shini.adminToken(), "Item " + i, String.valueOf(71000000 + i), 3, 5, "Dairy Co", 1.0, 30);
            long np = product(neshan.adminToken(), "Item " + i, String.valueOf(71000000 + i), 3, 5, "Dairy Co", 1.0, 30);
            // the SAME clientRequestId in both stores: independent orders
            String key = UUID.randomUUID().toString();
            work.add(new String[]{shini.adminToken(), orderJson(key, sDairy, "MANUAL", null, items(sp, 1), null)});
            work.add(new String[]{neshan.adminToken(), orderJson(key, nDairy, "MANUAL", null, items(np, 1), null)});
        }
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (String[] w : work) calls.add(() -> call("POST", "/api/purchase-orders", w[0], w[1]));
        List<Answer> answers = together(calls);
        for (int i = 0; i < work.size(); i++) {
            for (int attempt = 0; attempt < 10 && answers.get(i).orderId() == null; attempt++) {
                answers.set(i, answer(call("POST", "/api/purchase-orders", work.get(i)[0], work.get(i)[1])));
            }
        }
        for (long store : new long[]{shini.storeId(), neshan.storeId()}) {
            assertThat(db.queryForList("SELECT order_number FROM purchase_orders WHERE store_id=? ORDER BY order_number",
                    String.class, store)).containsExactly("PO-000001", "PO-000002", "PO-000003", "PO-000004", "PO-000005");
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_items i JOIN purchase_orders o ON o.order_id = i.order_id "
                + "JOIN products p ON p.product_id = i.product_id WHERE p.store_id <> o.store_id", Long.class)).isZero();
    }
}
