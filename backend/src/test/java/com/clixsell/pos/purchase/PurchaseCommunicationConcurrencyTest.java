package com.clixsell.pos.purchase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 under real concurrent HTTP requests with FOUR database connections (production uses
 * one): each successful change creates exactly one event, client reports with the same
 * clientActionId are recorded once, a supplier acceptance once, and a loser always gets a
 * clean 409 (never 500, never half-saved). Also proves that the zero-row write lock really holds
 * SQLite's write lock inside the application's own transaction and changes nothing.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:test-clixsell.db?foreign_keys=on&busy_timeout=15000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
class PurchaseCommunicationConcurrencyTest extends PurchaseApiTestBase {

    private static final String P = "/api/purchase-orders/";
    private static final Set<String> CLEAN_LOSSES = Set.of("DATABASE_BUSY", "ORDER_CHANGED", "INVALID_TRANSITION",
            "ALREADY_RECORDED", "ORDER_FINAL", "CLIENT_ACTION_CONFLICT");

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private PurchaseQueries queries;

    @AfterEach
    void databaseStaysConsistent() {
        assertDatabaseConsistent();
    }

    private record Answer(int status, String code, Boolean duplicate) {
    }

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
        for (Future<MvcResult> f : futures) {
            MvcResult r = f.get(90, TimeUnit.SECONDS);
            String b = body(r);
            out.add(new Answer(status(r), b.contains("\"code\"") ? read(r, "$.code") : null,
                    b.contains("\"duplicate\"") ? read(r, "$.duplicate") : null));
        }
        pool.shutdown();
        System.out.println("CONCURRENCY " + out);
        for (Answer a : out) {
            assertThat(a.status()).as("never a server error: " + a).isIn(200, 409);
            if (a.status() == 409) assertThat(a.code()).as("a clean, documented loss").isIn(CLEAN_LOSSES);
        }
        return out;
    }

    private static long ok(List<Answer> answers) {
        return answers.stream().filter(a -> a.status() == 200).count();
    }

    private long dairy;

    private long newProduct(String name, String barcode) throws Exception {
        if (dairy == 0) dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        return product(shini.adminToken(), name, barcode, 3, 5, "Dairy Co", 2.0, 30);
    }

    private MvcResult post(String path, String token, String body) throws Exception {
        return call("POST", P + path, token, body);
    }

    private long approved(long product) throws Exception {
        long id = createOk(shini.adminToken(), dairy, "MANUAL", product, 4);
        post(id + "/approve", shini.adminToken(), "{\"version\":0}");
        return id;
    }

    private String token(long id) throws Exception {
        return read(call("GET", P + id + "/message-preview", shini.adminToken(), null), "$.previewToken");
    }

    private long generated(long product) throws Exception {
        long id = approved(product);
        MvcResult r = post(id + "/message", shini.adminToken(),
                "{\"version\":1,\"language\":\"ar\",\"previewToken\":\"" + token(id) + "\"}");
        if (status(r) != 200) throw new IllegalStateException(body(r));
        return id;
    }

    private long count(long id, String type) {
        return db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE order_id = ? AND event_type = ?",
                Long.class, id, type);
    }

    // ── the write lock itself ───────────────────────────────────────────────────────

    /** Another connection asks for the write lock without waiting. */
    private static boolean otherWriterGetsTheLock() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:test-clixsell.db?busy_timeout=0");
             Statement s = c.createStatement()) {
            try {
                s.execute("BEGIN IMMEDIATE");
                s.execute("ROLLBACK");
                return true;
            } catch (SQLException e) {
                if (e.getMessage().contains("SQLITE_BUSY") || e.getMessage().contains("locked")) return false;
                throw e;
            }
        }
    }

    @Test
    void theZeroRowUpdateHoldsTheWriteLockInTheApplicationTransactionAndChangesNothing() throws Exception {
        List<Map<String, Object>> stores = db.queryForList("SELECT * FROM stores ORDER BY store_id");
        long activity = db.queryForObject("SELECT COUNT(*) FROM activity_log", Long.class);
        assertThat(otherWriterGetsTheLock()).as("control: nobody holds the lock").isTrue();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Boolean otherDuring = tx.execute(status -> {
            queries.takeWriteLock(); // first statement, like the service
            try {
                return otherWriterGetsTheLock();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(otherDuring).as("while the transaction holds it, no other writer can write").isFalse();
        assertThat(otherWriterGetsTheLock()).as("released at commit").isTrue();
        assertThat(db.queryForList("SELECT * FROM stores ORDER BY store_id")).as("no store row, setting or timestamp changed")
                .isEqualTo(stores);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activity_log", Long.class)).isEqualTo(activity);
        assertThat(db.update("UPDATE stores SET store_id = store_id WHERE 0")).as("matches no row").isZero();
        // a transaction that only reads first and writes later is refused at once (lock upgrade):
        // the reason the lock is taken FIRST
        assertThatThrownBy(() -> {
            try (Connection holder = DriverManager.getConnection("jdbc:sqlite:test-clixsell.db");
                 Statement h = holder.createStatement()) {
                tx.execute(status -> {
                    db.queryForObject("SELECT COUNT(*) FROM stores", Long.class); // SHARED
                    try {
                        h.execute("BEGIN IMMEDIATE");
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    queries.takeWriteLock();
                    return null;
                });
            }
        }).hasMessageContaining("locked");
    }

    // ── concurrent requests ─────────────────────────────────────────────────────────

    @Test
    void sixGenerationsOfTheSameVersionGenerateOnce() throws Exception {
        long id = approved(newProduct("Labneh", "11111111"));
        String t = token(id);
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String who = i % 2 == 0 ? shini.adminToken() : shiniAdmin2;
            calls.add(() -> post(id + "/message", who, "{\"version\":1,\"language\":\"ar\",\"previewToken\":\"" + t + "\"}"));
        }
        assertThat(ok(together(calls))).isEqualTo(1);
        assertThat(state(id)).isEqualTo("MESSAGE_GENERATED");
        assertThat(version(id)).isEqualTo(2);
        assertThat(count(id, "MESSAGE_GENERATED")).isEqualTo(1);
    }

    @Test
    void theSameCopyOrOpeningReportFiveTimesAtOnceIsRecordedOnce() throws Exception {
        long id = generated(newProduct("Labneh", "11111111"));
        String copy = UUID.randomUUID().toString();
        String open = UUID.randomUUID().toString();
        List<Callable<MvcResult>> copies = new ArrayList<>();
        List<Callable<MvcResult>> opens = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            copies.add(() -> post(id + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"" + copy + "\"}"));
            opens.add(() -> post(id + "/whatsapp-open-requested", shiniAdmin2,
                    "{\"clientActionId\":\"" + open + "\",\"confirmed\":true}"));
        }
        List<Answer> c = together(copies);
        assertThat(ok(c)).isEqualTo(5);
        assertThat(c.stream().filter(a -> Boolean.FALSE.equals(a.duplicate())).count()).as("exactly one new record").isEqualTo(1);
        List<Answer> o = together(opens);
        assertThat(o.stream().filter(a -> Boolean.FALSE.equals(a.duplicate())).count()).isEqualTo(1);
        assertThat(count(id, "MESSAGE_COPIED")).isEqualTo(1);
        assertThat(count(id, "WHATSAPP_OPEN_REQUESTED")).isEqualTo(1);

        // the client result of that request, five times at once (and once with another result)
        List<Callable<MvcResult>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(() -> post(id + "/whatsapp-open-result", shini.adminToken(),
                    "{\"clientActionId\":\"" + open + "\",\"result\":\"OPENED\"}"));
        }
        results.add(() -> post(id + "/whatsapp-open-result", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"result\":\"FAILED\"}"));
        List<Answer> r = together(results);
        assertThat(r.stream().filter(a -> Boolean.FALSE.equals(a.duplicate())).count()).isEqualTo(1);
        assertThat(count(id, "WHATSAPP_OPEN_REQUESTED")).as("REQUESTED + one CLIENT_REPORTED").isEqualTo(2);
        assertThat(db.queryForList("SELECT json_extract(details, '$.phase') FROM purchase_order_events WHERE order_id = ? "
                + "AND event_type = 'WHATSAPP_OPEN_REQUESTED' ORDER BY event_id", String.class, id))
                .containsExactly("REQUESTED", "CLIENT_REPORTED");
        assertThat(version(id)).isEqualTo(2);
    }

    @Test
    void markSentAndCancelAtOnceEndInExactlyOneOutcome() throws Exception {
        long id = generated(newProduct("Labneh", "11111111"));
        List<Callable<MvcResult>> calls = List.of(
                () -> post(id + "/mark-sent", shini.adminToken(), "{\"version\":2,\"confirmSentInWhatsapp\":true}"),
                () -> post(id + "/cancel", shiniAdmin2,
                        "{\"version\":2,\"reason\":\"Changed\",\"contactStatus\":\"NOT_SENT\",\"confirmNotSent\":true}"),
                () -> post(id + "/mark-sent", shiniAdmin2, "{\"version\":2,\"confirmSentInWhatsapp\":true}"),
                () -> post(id + "/cancel", shini.adminToken(), "{\"version\":2,\"reason\":\"Changed\",\"contactStatus\":\"UNKNOWN\"}"));
        assertThat(ok(together(calls))).isEqualTo(1);
        assertThat(version(id)).isEqualTo(3);
        assertThat(count(id, "MARKED_SENT") + count(id, "CANCELED")).isEqualTo(1);
        if (state(id).equals("SENT")) {
            assertThat(db.queryForObject("SELECT sent_marked_at IS NOT NULL FROM purchase_orders WHERE order_id = ?",
                    Integer.class, id)).isEqualTo(1);
        } else {
            assertThat(state(id)).isEqualTo("CANCELED");
            assertThat(db.queryForObject("SELECT sent_marked_at FROM purchase_orders WHERE order_id = ?", String.class, id))
                    .isNull();
        }
    }

    @Test
    void supplierConfirmationAndCancellationAtOnceEndInExactlyOneOutcome() throws Exception {
        long id = generated(newProduct("Labneh", "11111111"));
        post(id + "/mark-sent", shini.adminToken(), "{\"version\":2,\"confirmSentInWhatsapp\":true}");
        List<Callable<MvcResult>> calls = List.of(
                () -> post(id + "/supplier-confirmation", shini.adminToken(), "{\"version\":3,\"confirmSupplierConfirmed\":true}"),
                () -> post(id + "/cancel", shiniAdmin2,
                        "{\"version\":3,\"reason\":\"Too slow\",\"acknowledgeSupplierNotNotified\":true}"),
                () -> post(id + "/supplier-confirmation", shiniAdmin2, "{\"version\":3,\"confirmSupplierConfirmed\":true}"));
        assertThat(ok(together(calls))).isEqualTo(1);
        assertThat(version(id)).isEqualTo(4);
        assertThat(count(id, "SUPPLIER_CONFIRMATION_RECORDED") + count(id, "CANCELED")).isEqualTo(1);
        assertThat(state(id)).isIn("CONFIRMED", "CANCELED");
    }

    @Test
    void closingTwiceAtOnceClosesOnce() throws Exception {
        long id = generated(newProduct("Labneh", "11111111"));
        post(id + "/mark-sent", shini.adminToken(), "{\"version\":2,\"confirmSentInWhatsapp\":true}");
        post(id + "/supplier-confirmation", shini.adminToken(), "{\"version\":3,\"confirmSupplierConfirmed\":true}");
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.add(() -> post(id + "/close", shini.adminToken(), "{\"version\":4,\"confirmNoReceiving\":true}"));
        }
        assertThat(ok(together(calls))).isEqualTo(1);
        assertThat(count(id, "CLOSED")).isEqualTo(1);
        assertThat(version(id)).isEqualTo(5);
    }

    @Test
    void theSupplierAcceptanceFiveTimesAtOnceIsRecordedOnce() throws Exception {
        long id = generated(newProduct("Labneh", "11111111"));
        post(id + "/cancel", shini.adminToken(), "{\"version\":2,\"reason\":\"Changed\",\"contactStatus\":\"UNKNOWN\"}");
        List<Callable<MvcResult>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String who = i % 2 == 0 ? shini.adminToken() : shiniAdmin2;
            calls.add(() -> post(id + "/supplier-cancellation-recorded", who,
                    "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"Supplier agreed\"}"));
        }
        List<Answer> a = together(calls);
        assertThat(ok(a)).isEqualTo(1);
        assertThat(a.stream().filter(x -> "ALREADY_RECORDED".equals(x.code())).count()).isEqualTo(4);
        assertThat(count(id, "SUPPLIER_CANCELLATION_RECORDED")).isEqualTo(1);
    }

    @Test
    void aCopyRacingACancellationIsNeverRecordedAfterTheCancellation() throws Exception {
        for (int round = 0; round < 3; round++) {
            long id = generated(newProduct("Item " + round, "9900000" + round));
            List<Callable<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String a = UUID.randomUUID().toString();
                calls.add(() -> post(id + "/message-copied", shiniAdmin2, "{\"clientActionId\":\"" + a + "\"}"));
            }
            calls.add(() -> post(id + "/cancel", shini.adminToken(),
                    "{\"version\":2,\"reason\":\"Changed\",\"contactStatus\":\"UNKNOWN\"}"));
            together(calls);
            assertThat(state(id)).isEqualTo("CANCELED");
            Long cancelId = db.queryForObject("SELECT event_id FROM purchase_order_events WHERE order_id = ? "
                    + "AND event_type = 'CANCELED'", Long.class, id);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE order_id = ? "
                    + "AND event_type = 'MESSAGE_COPIED' AND event_id > ?", Long.class, id, cancelId))
                    .as("no copy report after the order became final").isZero();
            // the CANCELED event counted exactly the copies recorded before it
            long before = db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE order_id = ? "
                    + "AND event_type = 'MESSAGE_COPIED' AND event_id < ?", Long.class, id, cancelId);
            assertThat(db.queryForObject("SELECT json_extract(details, '$.priorCopyReports') FROM purchase_order_events "
                    + "WHERE event_id = ?", Long.class, cancelId)).isEqualTo(before);
        }
    }
}
