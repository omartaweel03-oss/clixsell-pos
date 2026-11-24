package com.clixsell.pos.purchase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DATABASE_BUSY path, reproduced deterministically: another connection holds SQLite's write
 * lock while an order is created. The creation has already read (SHARED lock) when it needs to
 * write, so SQLite refuses at once (no waiting, to avoid a deadlock) on every attempt; after the
 * service's retries the client gets 409 DATABASE_BUSY and NOTHING is saved. Retrying the exact
 * same request (same clientRequestId) then creates the order once, and again returns it.
 */
class PurchaseBusyRetryTest extends PurchaseApiTestBase {

    private Connection holder;

    @AfterEach
    void releaseAndCheck() throws Exception {
        if (holder != null) {
            try (Statement s = holder.createStatement()) {
                s.execute("ROLLBACK");
            } catch (Exception ignored) {
                // already released
            }
            holder.close();
        }
        assertDatabaseConsistent();
    }

    /** Another writer: takes the database write lock (RESERVED) and keeps it. */
    private void holdWriteLock() throws Exception {
        holder = DriverManager.getConnection("jdbc:sqlite:test-clixsell.db");
        try (Statement s = holder.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
        }
    }

    private void releaseWriteLock() throws Exception {
        try (Statement s = holder.createStatement()) {
            s.execute("ROLLBACK");
        }
    }

    @Test
    void busyAfterAllRetriesSavesNothingAndTheSameRequestLaterCreatesTheOrderOnce() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        String key = UUID.randomUUID().toString();
        String json = orderJson(key, dairy, "MANUAL", "Weekly", items(labneh, 5), null);

        holdWriteLock();
        long start = System.nanoTime();
        MvcResult busy = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println("BUSY answer after " + ms + " ms: " + status(busy) + " " + body(busy));

        // an actionable answer for the app: a stable code and a plain retry message
        assertThat(status(busy)).isEqualTo(409);
        assertThat((String) read(busy, "$.code")).isEqualTo("DATABASE_BUSY");
        assertThat((String) read(busy, "$.message")).contains("nothing was saved").contains("try again");
        releaseWriteLock();

        // nothing at all was saved: no order, no line, no reservation, no event, no number taken
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_items", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_events", Long.class)).isZero();
        assertThat(reservedBy(labneh)).isNull();
        assertThat(counter(shini.storeId())).isEqualTo(1);

        // the exact same request again → created once, with the first number
        MvcResult retry = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        assertThat(status(retry)).isEqualTo(201);
        assertThat((String) read(retry, "$.orderNumber")).isEqualTo("PO-000001");
        long id = ((Number) read(retry, "$.orderId")).longValue();
        // and once more → the same order (replayed), still one of everything
        MvcResult again = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        assertThat(status(again)).isEqualTo(200);
        assertThat((Boolean) read(again, "$.replayed")).isTrue();
        assertThat(((Number) read(again, "$.orderId")).longValue()).isEqualTo(id);

        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE open_product_id = ?", Long.class, labneh))
                .isEqualTo(1);
        assertThat(eventTypes(id)).containsExactly("CREATED");
        assertThat(counter(shini.storeId())).isEqualTo(2);
        assertThat(db.queryForObject("SELECT client_request_id FROM purchase_orders WHERE order_id = ?", String.class, id))
                .isEqualTo(key);
    }

    @Test
    void aShortLockIsAbsorbedByTheServiceRetriesWithoutTheClientNoticing() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        String json = orderJson(UUID.randomUUID().toString(), dairy, "MANUAL", null, items(labneh, 5), null);

        holdWriteLock();
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(150);
                releaseWriteLock();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        MvcResult r = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        release.get(5, TimeUnit.SECONDS);
        assertThat(status(r)).as(body(r)).isEqualTo(201);
        long id = ((Number) read(r, "$.orderId")).longValue();
        assertThat(eventTypes(id)).containsExactly("CREATED");
        assertThat(counter(shini.storeId())).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(1);
    }

    @Test
    void approvalBusyAfterAllRetriesChangesNothingAndCanBeRetried() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long id = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);

        holdWriteLock();
        MvcResult busy = call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":0}");
        releaseWriteLock();
        assertThat((String) read(busy, "$.code")).isEqualTo("DATABASE_BUSY");
        assertThat(state(id)).isEqualTo("PENDING_APPROVAL");
        assertThat(version(id)).isZero();
        assertThat(eventTypes(id)).containsExactly("CREATED");

        // the same request (same version) succeeds afterwards, exactly once
        assertThat(status(call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":0}")))
                .isEqualTo(200);
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED");
        assertThat(version(id)).isEqualTo(1);
    }
}
