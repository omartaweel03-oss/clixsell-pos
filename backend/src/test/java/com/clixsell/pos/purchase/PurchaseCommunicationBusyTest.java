package com.clixsell.pos.purchase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATABASE_BUSY for Phase 3 actions: another connection holds SQLite's write lock for longer
 * than all the service's retries (a short busy_timeout keeps the test fast). The answer is
 * 409 DATABASE_BUSY, NOTHING is saved (no state change, no event), and the same request sent
 * again afterwards succeeds exactly once.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:test-clixsell.db?foreign_keys=on&busy_timeout=50")
class PurchaseCommunicationBusyTest extends PurchaseApiTestBase {

    private Connection holder;

    @AfterEach
    void release() throws Exception {
        if (holder != null && !holder.isClosed()) holder.close();
        assertDatabaseConsistent();
    }

    private void hold() throws Exception {
        holder = DriverManager.getConnection("jdbc:sqlite:test-clixsell.db");
        try (Statement s = holder.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
        }
    }

    private void letGo() throws Exception {
        try (Statement s = holder.createStatement()) {
            s.execute("ROLLBACK");
        }
        holder.close();
    }

    @Test
    void busyMarkSentAndBusyCopySaveNothingAndTheSameRequestsLaterSucceedOnce() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long id = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);
        call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":0}");
        String token = read(call("GET", "/api/purchase-orders/" + id + "/message-preview", shini.adminToken(), null),
                "$.previewToken");
        String generate = "{\"version\":1,\"language\":\"ar\",\"previewToken\":\"" + token + "\"}";

        hold();
        MvcResult busy = call("POST", "/api/purchase-orders/" + id + "/message", shini.adminToken(), generate);
        letGo();
        assertThat(status(busy)).isEqualTo(409);
        assertThat((String) read(busy, "$.code")).isEqualTo("DATABASE_BUSY");
        assertThat(state(id)).isEqualTo("APPROVED");
        assertThat(db.queryForObject("SELECT message_text FROM purchase_orders WHERE order_id = ?", String.class, id)).isNull();
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED");
        assertThat(status(call("POST", "/api/purchase-orders/" + id + "/message", shini.adminToken(), generate))).isEqualTo(200);

        String copy = "{\"clientActionId\":\"" + UUID.randomUUID() + "\"}";
        hold();
        MvcResult busyCopy = call("POST", "/api/purchase-orders/" + id + "/message-copied", shini.adminToken(), copy);
        MvcResult busySent = call("POST", "/api/purchase-orders/" + id + "/mark-sent", shini.adminToken(),
                "{\"version\":2,\"confirmSentInWhatsapp\":true}");
        letGo();
        assertThat((String) read(busyCopy, "$.code")).isEqualTo("DATABASE_BUSY");
        assertThat((String) read(busySent, "$.code")).isEqualTo("DATABASE_BUSY");
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED", "MESSAGE_GENERATED");
        assertThat(state(id)).isEqualTo("MESSAGE_GENERATED");
        assertThat(version(id)).isEqualTo(2);

        // the same requests again: each recorded once
        assertThat((Boolean) read(call("POST", "/api/purchase-orders/" + id + "/message-copied", shini.adminToken(), copy),
                "$.duplicate")).isFalse();
        assertThat((Boolean) read(call("POST", "/api/purchase-orders/" + id + "/message-copied", shini.adminToken(), copy),
                "$.duplicate")).isTrue();
        assertThat(status(call("POST", "/api/purchase-orders/" + id + "/mark-sent", shini.adminToken(),
                "{\"version\":2,\"confirmSentInWhatsapp\":true}"))).isEqualTo(200);
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED", "MESSAGE_GENERATED", "MESSAGE_COPIED", "MARKED_SENT");
    }
}
