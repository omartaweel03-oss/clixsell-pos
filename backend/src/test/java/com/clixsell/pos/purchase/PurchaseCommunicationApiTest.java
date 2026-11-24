package com.clixsell.pos.purchase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 — supplier communication through the real API on the TEST database: message preview
 * and generation, client reports (copy, WhatsApp opening request and result), the manager's
 * manual records (sent, supplier confirmation, close, supplier acceptance of a cancellation),
 * cancellation after the message, reorder warnings, every forbidden transition, isolation and
 * roles, and that nothing financial or in stock changes. No real WhatsApp, no supplier contact:
 * links are only read as text.
 */
class PurchaseCommunicationApiTest extends PurchaseApiTestBase {

    private static final String P = "/api/purchase-orders/";

    @AfterEach
    void databaseStaysConsistent() {
        assertDatabaseConsistent();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private long dairy;
    private long labneh;
    private long milk;

    private void shiniData() throws Exception {
        dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
    }

    private MvcResult post(String path, String token, String body) throws Exception {
        return call("POST", P + path, token, body);
    }

    private long approved(long... pairs) throws Exception {
        long id = createOk(shini.adminToken(), dairy, "MANUAL", pairs);
        MvcResult r = post(id + "/approve", shini.adminToken(), "{\"version\":0}");
        if (status(r) != 200) throw new IllegalStateException(body(r));
        return id;
    }

    private MvcResult preview(long id, String lang) throws Exception {
        return call("GET", P + id + "/message-preview" + (lang == null ? "" : "?language=" + lang), shini.adminToken(), null);
    }

    private MvcResult generate(long id, String lang) throws Exception {
        MvcResult p = preview(id, lang);
        if (status(p) != 200) return p;
        return post(id + "/message", shini.adminToken(), "{\"version\":%d,\"language\":\"%s\",\"previewToken\":\"%s\"}"
                .formatted(version(id), lang, read(p, "$.previewToken")));
    }

    private long generated(long... pairs) throws Exception {
        long id = approved(pairs);
        MvcResult r = generate(id, "ar");
        if (status(r) != 200) throw new IllegalStateException(body(r));
        return id;
    }

    private long sent(long... pairs) throws Exception {
        long id = generated(pairs);
        expectOk(post(id + "/mark-sent", shini.adminToken(), "{\"version\":2,\"confirmSentInWhatsapp\":true}"));
        return id;
    }

    private long confirmed(long... pairs) throws Exception {
        long id = sent(pairs);
        expectOk(post(id + "/supplier-confirmation", shini.adminToken(), "{\"version\":3,\"confirmSupplierConfirmed\":true}"));
        return id;
    }

    private static MvcResult expectOk(MvcResult r) throws Exception {
        if (status(r) != 200) throw new IllegalStateException(body(r));
        return r;
    }

    private static String code(MvcResult r) throws Exception {
        return read(r, "$.code");
    }

    private static String action() {
        return UUID.randomUUID().toString();
    }

    private String messageText(long id) {
        return db.queryForObject("SELECT message_text FROM purchase_orders WHERE order_id = ?", String.class, id);
    }

    private String lastDetails(long id, String type) {
        return db.queryForObject("SELECT details FROM purchase_order_events WHERE order_id = ? AND event_type = ? "
                + "ORDER BY event_id DESC LIMIT 1", String.class, id, type);
    }

    private List<String> candidateCautions(long productId) throws Exception {
        MvcResult r = call("GET", "/api/purchase-candidates?supplierId=" + dairy, shini.adminToken(), null);
        return read(r, "$.products[?(@.productId==" + productId + ")].cautions[*].type");
    }

    // ── preview and generation ──────────────────────────────────────────────────────

    @Test
    void thePreviewIsForApprovedOrdersOnlySavesNothingAndHasTheOrderButNoCostOrInternalNote() throws Exception {
        shiniData();
        String json = orderJson(UUID.randomUUID().toString(), dairy, "MANUAL", "INTERNAL-NOTE-do-not-send",
                items(labneh, 29, milk, 6), null);
        MvcResult c = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        long id = ((Number) read(c, "$.orderId")).longValue();
        assertThat(code(preview(id, "ar"))).isEqualTo("INVALID_TRANSITION");
        expectOk(post(id + "/approve", shini.adminToken(), "{\"version\":0}"));
        long eventsBefore = events(id);

        MvcResult ar = expectOk(preview(id, null)); // Arabic by default
        String text = read(ar, "$.text");
        assertThat((String) read(ar, "$.language")).isEqualTo("ar");
        assertThat(text).contains((String) read(c, "$.orderNumber"), "Shini", "Dairy Co", "Labneh", "11111111", "Milk",
                "22222222", "⁨29⁩", "⁨6⁩", "الكمية");
        assertThat(text).doesNotContain("9.5", "3.0", "₪", "INTERNAL-NOTE", "cost", "token", "password");
        assertThat((String) read(ar, "$.whatsappNumber")).isEqualTo("970599000001");
        assertThat((Integer) read(ar, "$.length.length")).isEqualTo(WhatsappMessageBuilder.length(text));
        assertThat((Integer) read(ar, "$.length.limit")).isEqualTo(2000);
        assertThat((Boolean) read(ar, "$.length.fits")).isTrue();
        assertThat(body(ar)).doesNotContain("wa.me");
        MvcResult en = expectOk(preview(id, "en"));
        assertThat((String) read(en, "$.text")).contains("Qty:", "Labneh").doesNotContain("الكمية");
        assertThat(status(preview(id, "fr"))).isEqualTo(400);

        // nothing was saved by previews
        assertThat(state(id)).isEqualTo("APPROVED");
        assertThat(version(id)).isEqualTo(1);
        assertThat(events(id)).isEqualTo(eventsBefore);
        assertThat(messageText(id)).isNull();
    }

    @Test
    void aSupplierWithoutWhatsappOrInactiveCannotGetAMessage() throws Exception {
        shiniData();
        long noPhone = supplier(shini.adminToken(), "Bakery", null);
        long bread = product(shini.adminToken(), "Bread", "33333333", 3, 5, "Bakery", 2.0, 30);
        long a = createOk(shini.adminToken(), noPhone, "MANUAL", bread, 4);
        expectOk(post(a + "/approve", shini.adminToken(), "{\"version\":0}"));
        assertThat(code(preview(a, "ar"))).isEqualTo("SUPPLIER_NO_WHATSAPP");

        long b = approved(labneh, 5);
        expectOk(call("PUT", "/api/suppliers/" + dairy + "/status", shini.adminToken(), "{\"active\":false}"));
        assertThat(code(preview(b, "ar"))).isEqualTo("SUPPLIER_INACTIVE");
        assertThat(code(post(b + "/message", shini.adminToken(), "{\"version\":1,\"language\":\"ar\",\"previewToken\":\""
                + "0".repeat(64) + "\"}"))).isEqualTo("SUPPLIER_INACTIVE");
        assertThat(state(b)).isEqualTo("APPROVED");
        assertThat(state(a)).isEqualTo("APPROVED");
    }

    @Test
    void generatingStoresTheExactPreviewOnceAndItNeverChangesAfterwards() throws Exception {
        shiniData();
        long id = approved(labneh, 5);
        MvcResult p = expectOk(preview(id, "ar"));
        String token = read(p, "$.previewToken");
        String body = "{\"version\":%d,\"language\":\"ar\",\"previewToken\":\"%s\"}";
        assertThat(code(post(id + "/message", shini.adminToken(), body.formatted(0, token)))).isEqualTo("ORDER_CHANGED");
        assertThat(status(post(id + "/message", shini.adminToken(), "{\"version\":1,\"language\":\"ar\"}"))).isEqualTo(400);
        assertThat(status(post(id + "/message", shini.adminToken(), body.formatted(1, token).replace("}", ",\"text\":\"x\"}"))))
                .as("the client cannot send its own text").isEqualTo(400);
        // the supplier number changes after the preview → the manager must look again
        expectOk(call("PUT", "/api/suppliers/" + dairy, shini.adminToken(),
                "{\"name\":\"Dairy Co\",\"whatsappNumber\":\"970599000002\"}"));
        MvcResult changed = post(id + "/message", shini.adminToken(), body.formatted(1, token));
        assertThat(code(changed)).isEqualTo("PREVIEW_CHANGED");
        assertThat((String) read(changed, "$.details.preview.whatsappNumber")).isEqualTo("970599000002");
        assertThat(state(id)).isEqualTo("APPROVED");
        assertThat(events(id)).isEqualTo(2);

        MvcResult p2 = expectOk(preview(id, "ar"));
        MvcResult ok = expectOk(post(id + "/message", shini.adminToken(), body.formatted(1, (String) read(p2, "$.previewToken"))));
        assertThat((String) read(ok, "$.status")).isEqualTo("MESSAGE_GENERATED");
        assertThat(body(ok)).doesNotContain("wa.me");
        assertThat((String) read(ok, "$.message.text")).isEqualTo(read(p2, "$.text"));
        assertThat((Boolean) read(ok, "$.message.actionable")).isTrue();
        assertThat(db.queryForMap("SELECT message_text, message_language, message_whatsapp, message_generated_by, version "
                + "FROM purchase_orders WHERE order_id = ?", id)).containsEntry("message_text", read(p2, "$.text"))
                .containsEntry("message_language", "ar").containsEntry("message_whatsapp", "970599000002")
                .containsEntry("version", 2);
        assertThat(db.queryForObject("SELECT message_generated_by FROM purchase_orders WHERE order_id = ?", Long.class, id))
                .isEqualTo(adminId("shini.admin"));
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED", "MESSAGE_GENERATED");
        assertThat(lastDetails(id, "MESSAGE_GENERATED")).contains("\"sent\":false", "MESSAGE_PREPARED");

        // again → refused, nothing new
        assertThat(code(post(id + "/message", shini.adminToken(), body.formatted(2, (String) read(p2, "$.previewToken")))))
                .isEqualTo("INVALID_TRANSITION");
        assertThat(code(preview(id, "ar"))).isEqualTo("INVALID_TRANSITION");
        assertThat(events(id)).isEqualTo(3);

        // the product and the supplier change later: the stored message and number stay
        String stored = messageText(id);
        db.update("UPDATE products SET name = 'Labneh RENAMED' WHERE product_id = ?", labneh);
        expectOk(call("PUT", "/api/suppliers/" + dairy, shini.adminToken(),
                "{\"name\":\"Dairy Co\",\"whatsappNumber\":\"970599000003\"}"));
        MvcResult m = expectOk(call("GET", P + id + "/message", shini.adminToken(), null));
        assertThat((String) read(m, "$.text")).isEqualTo(stored).doesNotContain("RENAMED");
        assertThat((String) read(m, "$.whatsappNumber")).isEqualTo("970599000002");
        assertThat((Boolean) read(m, "$.numberChangedSinceGeneration")).isTrue();
        assertThat((String) read(m, "$.currentSupplierWhatsapp")).isEqualTo("970599000003");
        MvcResult open = expectOk(post(id + "/whatsapp-open-requested", shini.adminToken(),
                "{\"clientActionId\":\"" + action() + "\",\"confirmed\":true}"));
        assertThat((String) read(open, "$.whatsappUrl")).startsWith("https://wa.me/970599000002?text=");
        // the database itself refuses rewriting the message (T7)
        assertThatThrownBy(() -> db.update("UPDATE purchase_orders SET message_text = 'x', version = version + 1 "
                + "WHERE order_id = ?", id)).hasMessageContaining("PO_FIELD_SET_ONCE");
        assertThatThrownBy(() -> db.update("UPDATE purchase_orders SET message_whatsapp = '970599000003', "
                + "version = version + 1 WHERE order_id = ?", id)).hasMessageContaining("PO_FIELD_SET_ONCE");
    }

    @Test
    void englishCanBeChosenAndIsStoredAsEnglish() throws Exception {
        shiniData();
        long id = approved(labneh, 5);
        expectOk(generate(id, "en"));
        assertThat(db.queryForObject("SELECT message_language FROM purchase_orders WHERE order_id = ?", String.class, id))
                .isEqualTo("en");
        assertThat(messageText(id)).startsWith("Purchase order: ").contains("Qty: ⁨5⁩");
    }

    // ── client reports ──────────────────────────────────────────────────────────────

    @Test
    void copyAndOpeningReportsAreEventsOnlyNeverVerifiedAndNeverDuplicated() throws Exception {
        shiniData();
        long early = approved(milk, 2);
        assertThat(code(post(early + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"" + action() + "\"}")))
                .isEqualTo("MESSAGE_NOT_GENERATED");
        long id = generated(labneh, 5);
        String text = messageText(id);

        assertThat(status(post(id + "/message-copied", shini.adminToken(), "{}"))).isEqualTo(400);
        assertThat(status(post(id + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"nope\"}"))).isEqualTo(400);
        String copy = action();
        MvcResult c1 = expectOk(post(id + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"" + copy + "\"}"));
        assertThat((Boolean) read(c1, "$.verified")).isFalse();
        assertThat((Boolean) read(c1, "$.duplicate")).isFalse();
        assertThat((String) read(c1, "$.recordedAs")).isEqualTo("CLIENT_REPORTED_COPY");
        MvcResult c2 = expectOk(post(id + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"" + copy.toUpperCase() + "\"}"));
        assertThat((Boolean) read(c2, "$.duplicate")).isTrue();
        assertThat(((Number) read(c2, "$.eventId")).longValue()).isEqualTo(((Number) read(c1, "$.eventId")).longValue());
        // the same id cannot become another action
        assertThat(code(post(id + "/whatsapp-open-requested", shini.adminToken(),
                "{\"clientActionId\":\"" + copy + "\",\"confirmed\":true}"))).isEqualTo("CLIENT_ACTION_CONFLICT");

        String open = action();
        assertThat(status(post(id + "/whatsapp-open-requested", shini.adminToken(), "{\"clientActionId\":\"" + open + "\"}")))
                .as("the manager's confirmation is required").isEqualTo(400);
        assertThat(status(post(id + "/whatsapp-open-requested", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"confirmed\":false}"))).isEqualTo(400);
        assertThat(code(post(id + "/whatsapp-open-result", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"result\":\"OPENED\"}"))).isEqualTo("OPEN_NOT_REQUESTED");
        MvcResult o1 = expectOk(post(id + "/whatsapp-open-requested", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"confirmed\":true}"));
        String url = read(o1, "$.whatsappUrl");
        assertThat(url).startsWith("https://wa.me/970599000001?text=");
        assertThat(URLDecoder.decode(url.substring(url.indexOf("text=") + 5), StandardCharsets.UTF_8)).isEqualTo(text);
        assertThat((Boolean) read(o1, "$.verified")).isFalse();
        MvcResult o2 = expectOk(post(id + "/whatsapp-open-requested", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"confirmed\":true}"));
        assertThat((Boolean) read(o2, "$.duplicate")).isTrue();

        // the client-reported result shares the request's id: a second, distinct event
        assertThat(status(post(id + "/whatsapp-open-result", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"result\":\"SENT\"}"))).isEqualTo(400);
        MvcResult r1 = expectOk(post(id + "/whatsapp-open-result", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"result\":\"OPENED\"}"));
        assertThat((String) read(r1, "$.phase")).isEqualTo("CLIENT_REPORTED");
        assertThat((Boolean) read(expectOk(post(id + "/whatsapp-open-result", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"result\":\"OPENED\"}")), "$.duplicate")).isTrue();
        MvcResult conflict = post(id + "/whatsapp-open-result", shini.adminToken(),
                "{\"clientActionId\":\"" + open + "\",\"result\":\"FAILED\"}");
        assertThat(code(conflict)).isEqualTo("CLIENT_ACTION_CONFLICT");
        assertThat((String) read(conflict, "$.details.recordedResult")).isEqualTo("OPENED");

        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED", "MESSAGE_GENERATED", "MESSAGE_COPIED",
                "WHATSAPP_OPEN_REQUESTED", "WHATSAPP_OPEN_REQUESTED");
        List<String> phases = db.queryForList("SELECT json_extract(details, '$.phase') FROM purchase_order_events "
                + "WHERE order_id = ? AND event_type = 'WHATSAPP_OPEN_REQUESTED' ORDER BY event_id", String.class, id);
        assertThat(phases).containsExactly("REQUESTED", "CLIENT_REPORTED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE order_id = ? AND event_type IN "
                + "('MESSAGE_COPIED','WHATSAPP_OPEN_REQUESTED') AND json_extract(details, '$.verified') = 0", Long.class, id))
                .isEqualTo(3);
        // none of it changed the order or claimed a sending
        assertThat(state(id)).isEqualTo("MESSAGE_GENERATED");
        assertThat(version(id)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT sent_marked_at FROM purchase_orders WHERE order_id = ?", String.class, id)).isNull();
        MvcResult view = expectOk(call("GET", P + id, shini.adminToken(), null));
        assertThat((Integer) read(view, "$.communication.copyReports")).isEqualTo(1);
        assertThat((Integer) read(view, "$.communication.openRequests")).isEqualTo(1);
        assertThat((Integer) read(view, "$.communication.clientOpenReports")).isEqualTo(1);
        assertThat((Boolean) read(view, "$.communication.sentRecordedByManager")).isFalse();
        // an action id of this order cannot be used on another order
        long other = generated(product(shini.adminToken(), "Yogurt", "44444444", 3, 5, "Dairy Co", 2.0, 30), 3);
        assertThat(code(post(other + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"" + copy + "\"}")))
                .isEqualTo("CLIENT_ACTION_CONFLICT");
    }

    // ── the manual records and the full lifecycle ───────────────────────────────────

    @Test
    void theFullLifecycleRecordsEachManualStepOnceAndClosingIsNotReceiving() throws Exception {
        shiniData();
        long id = generated(labneh, 5, milk, 4);
        String copy = action();
        expectOk(post(id + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"" + copy + "\"}"));

        assertThat(status(post(id + "/mark-sent", shini.adminToken(), "{\"version\":2}"))).isEqualTo(400);
        assertThat(status(post(id + "/mark-sent", shini.adminToken(), "{\"version\":2,\"confirmSentInWhatsapp\":false}")))
                .isEqualTo(400);
        assertThat(code(post(id + "/mark-sent", shini.adminToken(), "{\"version\":1,\"confirmSentInWhatsapp\":true}")))
                .isEqualTo("ORDER_CHANGED");
        MvcResult sent = expectOk(post(id + "/mark-sent", shiniAdmin2, "{\"version\":2,\"confirmSentInWhatsapp\":true}"));
        assertThat((String) read(sent, "$.status")).isEqualTo("SENT");
        assertThat((Boolean) read(sent, "$.communication.sentRecordedByManager")).isTrue();
        assertThat(db.queryForObject("SELECT sent_marked_by FROM purchase_orders WHERE order_id = ?", Long.class, id))
                .isEqualTo(adminId("shini.admin2"));
        assertThat(lastDetails(id, "MARKED_SENT")).contains("\"recordedManually\":true", "\"verified\":false",
                "\"deliveryConfirmed\":false", "\"priorCopyReports\":1", "MANAGER_RECORDED_SENDING");
        assertThat(code(post(id + "/mark-sent", shini.adminToken(), "{\"version\":3,\"confirmSentInWhatsapp\":true}")))
                .isEqualTo("INVALID_TRANSITION");

        assertThat(status(post(id + "/supplier-confirmation", shini.adminToken(), "{\"version\":3}"))).isEqualTo(400);
        assertThat(status(post(id + "/supplier-confirmation", shini.adminToken(),
                "{\"version\":3,\"confirmSupplierConfirmed\":true,\"note\":\"" + "n".repeat(301) + "\"}"))).isEqualTo(400);
        MvcResult conf = expectOk(post(id + "/supplier-confirmation", shini.adminToken(),
                "{\"version\":3,\"confirmSupplierConfirmed\":true,\"note\":\"  Abu Ahmad confirmed by phone  \"}"));
        assertThat((String) read(conf, "$.status")).isEqualTo("CONFIRMED");
        assertThat((String) read(conf, "$.communication.confirmationNote")).isEqualTo("Abu Ahmad confirmed by phone");
        assertThat(lastDetails(id, "SUPPLIER_CONFIRMATION_RECORDED")).contains("\"recordedManually\":true", "\"verified\":false");
        assertThat(reservedBy(labneh)).isEqualTo(id);

        int stockBefore = db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, labneh);
        assertThat(status(post(id + "/close", shini.adminToken(), "{\"version\":4}"))).isEqualTo(400);
        MvcResult closed = expectOk(post(id + "/close", shini.adminToken(), "{\"version\":4,\"confirmNoReceiving\":true}"));
        assertThat((String) read(closed, "$.status")).isEqualTo("CLOSED");
        assertThat((List<Object>) read(closed, "$.allowedActions")).containsExactly("VIEW_MESSAGE");
        assertThat((Boolean) read(closed, "$.message.actionable")).isFalse();
        assertThat(body(closed)).doesNotContain("wa.me");
        assertThat(db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, labneh))
                .as("closing is not receiving").isEqualTo(stockBefore);
        assertThat(reservedBy(labneh)).isNull();
        assertThat(reservedBy(milk)).isNull();
        assertThat(lastDetails(id, "CLOSED")).contains("\"receivingRecorded\":false", "\"stockChanged\":false");
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED", "MESSAGE_GENERATED", "MESSAGE_COPIED",
                "MARKED_SENT", "SUPPLIER_CONFIRMATION_RECORDED", "CLOSED");
        assertThat(version(id)).isEqualTo(5);

        // the closed order's message is read only; no link, no new reports
        assertThat(code(post(id + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"" + action() + "\"}")))
                .isEqualTo("ORDER_FINAL");
        assertThat(code(post(id + "/whatsapp-open-requested", shini.adminToken(),
                "{\"clientActionId\":\"" + action() + "\",\"confirmed\":true}"))).isEqualTo("ORDER_FINAL");
        MvcResult m = expectOk(call("GET", P + id + "/message", shini.adminToken(), null));
        assertThat((Boolean) read(m, "$.actionable")).isFalse();
        assertThat(body(m)).doesNotContain("wa.me");
        // the products can be ordered again, with the 14-day "recently closed" warning
        assertThat(candidateCautions(labneh)).containsExactly("RECENTLY_CLOSED");
        assertThat(code(create(shini.adminToken(), dairy, "MANUAL", labneh, 3))).isEqualTo("REORDER_CAUTION_ACK_REQUIRED");
        // the closed history cannot be changed in the database either
        assertThatThrownBy(() -> db.update("UPDATE purchase_orders SET confirmation_note = 'x', version = version + 1 "
                + "WHERE order_id = ?", id)).hasMessageContaining("PO_");
    }

    @Test
    void overdueFollowsTheApprovedThresholds() {
        // the recorded times cannot be moved back in the database (T7), so the rule is checked directly
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 11, 24, 12, 0);
        PurchaseOrder o = new PurchaseOrder(1, "PO-1", 1, "S", PurchaseOrder.Source.MANUAL, null, 1, now.minusDays(1),
                UUID.randomUUID().toString(), "a".repeat(64));
        o.approve(1, now.minusHours(2));
        assertThat(PurchaseOrderService.overdue(o, now)).isFalse();
        o.generateMessage(1, now.minusHours(23), "x", "ar", "970599000001");
        assertThat(PurchaseOrderService.overdue(o, now)).as("message prepared 23 h ago").isFalse();
        assertThat(PurchaseOrderService.overdue(o, now.plusHours(1))).as("1 day, sending not recorded").isTrue();
        o.markSent(1, now);
        assertThat(PurchaseOrderService.overdue(o, now.plusDays(2))).isFalse();
        assertThat(PurchaseOrderService.overdue(o, now.plusDays(3))).as("3 days without supplier confirmation").isTrue();
        o.recordSupplierConfirmation(1, now, null);
        assertThat(PurchaseOrderService.overdue(o, now.plusDays(13))).isFalse();
        assertThat(PurchaseOrderService.overdue(o, now.plusDays(14))).as("14 days confirmed, not closed").isTrue();
        o.close(1, now);
        assertThat(PurchaseOrderService.overdue(o, now.plusDays(100))).isFalse();
    }

    // ── every forbidden transition ──────────────────────────────────────────────────

    @Test
    void everyActionInAWrongStateIsRefusedAndChangesNothing() throws Exception {
        shiniData();
        List<Long> products = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            products.add(product(shini.adminToken(), "P" + i, "5555000" + i, 3, 5, "Dairy Co", 1.0, 30));
        }
        long pending = createOk(shini.adminToken(), dairy, "MANUAL", products.get(0), 1);
        long approved = approved(products.get(1), 1);
        long generated = generated(products.get(2), 1);
        long sent = sent(products.get(3), 1);
        long confirmed = confirmed(products.get(4), 1);
        long closed = confirmed(products.get(5), 1);
        expectOk(post(closed + "/close", shini.adminToken(), "{\"version\":4,\"confirmNoReceiving\":true}"));
        long rejected = createOk(shini.adminToken(), dairy, "MANUAL", products.get(6), 1);
        expectOk(post(rejected + "/reject", shini.adminToken(), "{\"version\":0,\"reason\":\"No need\"}"));
        long canceled = approved(products.get(7), 1);
        expectOk(post(canceled + "/cancel", shini.adminToken(), "{\"version\":1,\"reason\":\"No need\"}"));

        Map<String, Long> orders = new java.util.LinkedHashMap<>();
        orders.put("PENDING_APPROVAL", pending);
        orders.put("APPROVED", approved);
        orders.put("MESSAGE_GENERATED", generated);
        orders.put("SENT", sent);
        orders.put("CONFIRMED", confirmed);
        orders.put("CLOSED", closed);
        orders.put("REJECTED", rejected);
        orders.put("CANCELED", canceled);
        // action → the one state it is allowed in
        Map<String, List<String>> allowed = Map.of(
                "message", List.of("APPROVED"), "mark-sent", List.of("MESSAGE_GENERATED"),
                "supplier-confirmation", List.of("SENT"), "close", List.of("CONFIRMED"),
                "message-copied", List.of("MESSAGE_GENERATED", "SENT", "CONFIRMED"),
                "whatsapp-open-requested", List.of("MESSAGE_GENERATED", "SENT", "CONFIRMED"));
        int refused = 0;
        for (var o : orders.entrySet()) {
            long id = o.getValue();
            int v = version(id);
            long ev = events(id);
            for (var a : allowed.entrySet()) {
                if (a.getValue().contains(o.getKey())) continue;
                String body = switch (a.getKey()) {
                    case "message" -> "{\"version\":%d,\"language\":\"ar\",\"previewToken\":\"%s\"}".formatted(v, "0".repeat(64));
                    case "mark-sent" -> "{\"version\":%d,\"confirmSentInWhatsapp\":true}".formatted(v);
                    case "supplier-confirmation" -> "{\"version\":%d,\"confirmSupplierConfirmed\":true}".formatted(v);
                    case "close" -> "{\"version\":%d,\"confirmNoReceiving\":true}".formatted(v);
                    case "message-copied" -> "{\"clientActionId\":\"" + action() + "\"}";
                    default -> "{\"clientActionId\":\"" + action() + "\",\"confirmed\":true}";
                };
                MvcResult r = post(id + "/" + a.getKey(), shini.adminToken(), body);
                assertThat(status(r)).as(a.getKey() + " on " + o.getKey()).isEqualTo(409);
                assertThat(code(r)).as(a.getKey() + " on " + o.getKey())
                        .isIn("INVALID_TRANSITION", "MESSAGE_NOT_GENERATED", "ORDER_FINAL");
                refused++;
            }
            if (!o.getKey().equals("APPROVED")) {
                assertThat(code(preview(id, "ar"))).as("preview on " + o.getKey()).isEqualTo("INVALID_TRANSITION");
            }
            assertThat(version(id)).as(o.getKey() + " unchanged").isEqualTo(v);
            assertThat(events(id)).as(o.getKey() + " no event").isEqualTo(ev);
            assertThat(state(id)).isEqualTo(o.getKey());
        }
        assertThat(refused).isEqualTo(8 * 6 - 1 - 1 - 1 - 1 - 3 - 3);
        // the final states also refuse cancel, and the database refuses any skipped transition
        for (long id : List.of(closed, rejected, canceled)) {
            assertThat(code(post(id + "/cancel", shini.adminToken(), "{\"version\":" + version(id) + ",\"reason\":\"Again\"}")))
                    .isEqualTo("INVALID_TRANSITION");
        }
        assertThatThrownBy(() -> db.update("UPDATE purchase_orders SET status = 'SENT', version = version + 1 "
                + "WHERE order_id = ?", approved)).hasMessageContaining("PO_");
        assertThatThrownBy(() -> db.update("UPDATE purchase_orders SET status = 'CLOSED', closed_by = created_by, "
                + "closed_at = created_at, version = version + 1 WHERE order_id = ?", sent)).hasMessageContaining("PO_");
    }

    // ── cancellation after the message ──────────────────────────────────────────────

    @Test
    void cancellingAGeneratedOrderAlwaysNeedsTheContactStatusAndNeverClaimsSending() throws Exception {
        shiniData();
        long id = generated(labneh, 5);
        String cancel = "{\"version\":2,\"reason\":\"Plan changed\"%s}";
        MvcResult missing = post(id + "/cancel", shini.adminToken(), cancel.formatted(""));
        assertThat(code(missing)).as("even with no copy / opening reported").isEqualTo("CONTACT_STATUS_REQUIRED");
        assertThat((List<Object>) read(missing, "$.details.options")).containsExactly("NOT_SENT", "UNKNOWN", "SENT");
        assertThat(code(post(id + "/cancel", shini.adminToken(), cancel.formatted(",\"contactStatus\":\"SENT\""))))
                .isEqualTo("RECORD_SENT_FIRST");
        assertThat(status(post(id + "/cancel", shini.adminToken(), cancel.formatted(",\"contactStatus\":\"MAYBE\""))))
                .isEqualTo(400);
        assertThat(status(post(id + "/cancel", shini.adminToken(), cancel.formatted(",\"contactStatus\":\"NOT_SENT\""))))
                .as("NOT_SENT needs confirmNotSent: true").isEqualTo(400);
        assertThat(status(post(id + "/cancel", shini.adminToken(),
                cancel.formatted(",\"contactStatus\":\"UNKNOWN\",\"acknowledgeSupplierNotNotified\":true")))).isEqualTo(400);
        assertThat(status(post(id + "/cancel", shini.adminToken(),
                cancel.formatted(",\"contactStatus\":\"UNKNOWN\",\"confirmNotSent\":true")))).isEqualTo(400);
        assertThat(state(id)).isEqualTo("MESSAGE_GENERATED");
        assertThat(version(id)).isEqualTo(2);
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED", "MESSAGE_GENERATED");

        // UNKNOWN: canceled, never recorded as sent, warns for 30 days
        MvcResult unknown = expectOk(post(id + "/cancel", shini.adminToken(), cancel.formatted(",\"contactStatus\":\"UNKNOWN\"")));
        assertThat((String) read(unknown, "$.status")).isEqualTo("CANCELED");
        assertThat((String) read(unknown, "$.communication.contactStatusAtCancel")).isEqualTo("UNKNOWN");
        assertThat((Boolean) read(unknown, "$.communication.possibleSupplierContact")).isTrue();
        assertThat((List<Object>) read(unknown, "$.allowedActions")).containsExactly("VIEW_MESSAGE", "RECORD_SUPPLIER_CANCELLATION");
        assertThat(db.queryForMap("SELECT canceled_after_contact, sent_marked_at FROM purchase_orders WHERE order_id = ?", id))
                .containsEntry("canceled_after_contact", 0).containsEntry("sent_marked_at", null);
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED", "MESSAGE_GENERATED", "CANCELED");
        assertThat(lastDetails(id, "CANCELED")).contains("\"contactStatus\":\"UNKNOWN\"", "\"supplierNotifiedByClixSell\":false",
                "\"afterSupplierContact\":false");
        assertThat(reservedBy(labneh)).isNull();
        assertThat(candidateCautions(labneh)).containsExactly("CONTACT_UNKNOWN");
        MvcResult again = create(shini.adminToken(), dairy, "MANUAL", labneh, 4);
        assertThat(code(again)).isEqualTo("REORDER_CAUTION_ACK_REQUIRED");
        assertThat((List<Object>) read(again, "$.details.cautions[*].type")).containsExactly("CONTACT_UNKNOWN");
        MvcResult acked = call("POST", "/api/purchase-orders", shini.adminToken(), orderJson(UUID.randomUUID().toString(),
                dairy, "MANUAL", null, items(labneh, 4), List.of(new long[]{labneh, id})));
        assertThat(status(acked)).isEqualTo(201);
    }

    @Test
    void notSentIsADeclarationAndAfterACopyStillWarns() throws Exception {
        shiniData();
        long quiet = generated(labneh, 5);
        expectOk(post(quiet + "/cancel", shini.adminToken(),
                "{\"version\":2,\"reason\":\"Plan changed\",\"contactStatus\":\"NOT_SENT\",\"confirmNotSent\":true}"));
        assertThat(lastDetails(quiet, "CANCELED")).contains("\"contactStatus\":\"NOT_SENT\"", "\"managerDeclaredNotSent\":true",
                "\"priorCopyReports\":0");
        assertThat(candidateCautions(labneh)).as("declared not sent, never copied or opened").isEmpty();
        MvcResult v = expectOk(call("GET", P + quiet, shini.adminToken(), null));
        assertThat((Boolean) read(v, "$.communication.possibleSupplierContact")).isFalse();
        assertThat(code(post(quiet + "/supplier-cancellation-recorded", shini.adminToken(),
                "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"Called him\"}"))).isEqualTo("NOT_ELIGIBLE");

        long copied = generated(milk, 5);
        expectOk(post(copied + "/whatsapp-open-requested", shini.adminToken(),
                "{\"clientActionId\":\"" + action() + "\",\"confirmed\":true}"));
        expectOk(post(copied + "/cancel", shini.adminToken(),
                "{\"version\":2,\"reason\":\"Plan changed\",\"contactStatus\":\"NOT_SENT\",\"confirmNotSent\":true}"));
        assertThat(lastDetails(copied, "CANCELED")).contains("\"priorOpenRequests\":1");
        assertThat(candidateCautions(milk)).containsExactly("DECLARED_NOT_SENT");
        assertThat(db.queryForObject("SELECT canceled_after_contact FROM purchase_orders WHERE order_id = ?", Integer.class,
                copied)).isZero();
    }

    @Test
    void cancellingAfterRecordedSendingNeedsTheAcknowledgmentAndWarns() throws Exception {
        shiniData();
        long s = sent(labneh, 5);
        long c = confirmed(milk, 5);
        assertThat(code(post(s + "/cancel", shini.adminToken(), "{\"version\":3,\"reason\":\"Too slow\"}")))
                .isEqualTo("ACK_SUPPLIER_NOT_NOTIFIED");
        assertThat(code(post(s + "/cancel", shini.adminToken(),
                "{\"version\":3,\"reason\":\"Too slow\",\"acknowledgeSupplierNotNotified\":false}"))).isEqualTo("ACK_SUPPLIER_NOT_NOTIFIED");
        assertThat(status(post(s + "/cancel", shini.adminToken(),
                "{\"version\":3,\"reason\":\"Too slow\",\"contactStatus\":\"NOT_SENT\",\"acknowledgeSupplierNotNotified\":true}")))
                .isEqualTo(400);
        assertThat(state(s)).isEqualTo("SENT");
        for (long[] o : new long[][]{{s, 3}, {c, 4}}) {
            MvcResult r = expectOk(post(o[0] + "/cancel", shiniAdmin2,
                    "{\"version\":" + o[1] + ",\"reason\":\"Too slow\",\"acknowledgeSupplierNotNotified\":true}"));
            assertThat((Boolean) read(r, "$.communication.canceledAfterContact")).isTrue();
            assertThat(db.queryForObject("SELECT canceled_after_contact FROM purchase_orders WHERE order_id = ?",
                    Integer.class, o[0])).isEqualTo(1);
            assertThat(lastDetails(o[0], "CANCELED")).contains("\"afterSupplierContact\":true",
                    "\"supplierNotifiedByClixSell\":false", "\"acknowledgedSupplierNotNotified\":true");
        }
        assertThat(candidateCautions(labneh)).containsExactly("CANCELED_AFTER_CONTACT");
        assertThat(candidateCautions(milk)).containsExactly("CANCELED_AFTER_CONTACT");
        assertThat(reservedBy(labneh)).isNull();
        // the pre-message cancel rules of Phase 2 still refuse the new fields
        long pending = createOk(shini.adminToken(), dairy, "MANUAL",
                product(shini.adminToken(), "Cheese", "66666666", 3, 5, "Dairy Co", 1.0, 30), 1);
        assertThat(status(post(pending + "/cancel", shini.adminToken(),
                "{\"version\":0,\"reason\":\"No need\",\"contactStatus\":\"NOT_SENT\"}"))).isEqualTo(400);
        assertThat(status(post(pending + "/cancel", shini.adminToken(), "{\"version\":0,\"reason\":\"No need\"}"))).isEqualTo(200);
    }

    // ── supplier acceptance of a cancellation ───────────────────────────────────────

    @Test
    void theSupplierAcceptanceIsAManualRecordOnceAndEndsOnlyThatOrdersWarnings() throws Exception {
        shiniData();
        long unknown = generated(labneh, 5);
        expectOk(post(unknown + "/cancel", shini.adminToken(), "{\"version\":2,\"reason\":\"Plan changed\",\"contactStatus\":\"UNKNOWN\"}"));
        long after = sent(milk, 5);
        expectOk(post(after + "/cancel", shini.adminToken(),
                "{\"version\":3,\"reason\":\"Too slow\",\"acknowledgeSupplierNotNotified\":true}"));
        long open = approved(product(shini.adminToken(), "Cream", "77777777", 3, 5, "Dairy Co", 1.0, 30), 2);
        String rec = "/supplier-cancellation-recorded";
        assertThat(code(post(open + rec, shini.adminToken(),
                "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"Called him\"}"))).isEqualTo("NOT_ELIGIBLE");
        for (String bad : List.of("{\"channel\":\"PHONE\",\"note\":\"Called him\"}",
                "{\"confirmSupplierAccepted\":false,\"channel\":\"PHONE\",\"note\":\"Called him\"}",
                "{\"confirmSupplierAccepted\":true,\"channel\":\"EMAIL\",\"note\":\"Called him\"}",
                "{\"confirmSupplierAccepted\":true,\"note\":\"Called him\"}",
                "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\"}",
                "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"  \\t \"}",
                "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"ok\"}",
                "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"" + "n".repeat(301) + "\"}",
                "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"Called him\",\"verified\":true}")) {
            assertThat(status(post(unknown + rec, shini.adminToken(), bad))).as(bad).isEqualTo(400);
        }
        assertThat(events(unknown)).isEqualTo(4);
        assertThat(candidateCautions(labneh)).containsExactly("CONTACT_UNKNOWN");

        MvcResult ok = expectOk(post(unknown + rec, shiniAdmin2,
                "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"Abu Ahmad agreed at 10:00\"}"));
        assertThat((Boolean) read(ok, "$.communication.supplierCancellationRecorded")).isTrue();
        assertThat((List<Object>) read(ok, "$.allowedActions")).containsExactly("VIEW_MESSAGE");
        assertThat(lastDetails(unknown, "SUPPLIER_CANCELLATION_RECORDED")).contains("\"recordedManually\":true",
                "\"verified\":false", "\"channel\":\"PHONE\"", "Abu Ahmad agreed at 10:00");
        assertThat(db.queryForObject("SELECT user_id FROM purchase_order_events WHERE order_id = ? AND event_type = "
                + "'SUPPLIER_CANCELLATION_RECORDED'", Long.class, unknown)).isEqualTo(adminId("shini.admin2"));
        assertThat(state(unknown)).isEqualTo("CANCELED");
        // only this order's warning ends
        assertThat(candidateCautions(labneh)).isEmpty();
        assertThat(candidateCautions(milk)).containsExactly("CANCELED_AFTER_CONTACT");
        assertThat(code(post(unknown + rec, shini.adminToken(),
                "{\"confirmSupplierAccepted\":true,\"channel\":\"OTHER\",\"note\":\"Again later\"}"))).isEqualTo("ALREADY_RECORDED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_events WHERE event_type = "
                + "'SUPPLIER_CANCELLATION_RECORDED'", Long.class)).isEqualTo(1);
    }

    // ── long messages ───────────────────────────────────────────────────────────────

    private List<Long> longProducts(int n, String prefix) throws Exception {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String name = (prefix + i + " ").concat("x".repeat(80)).substring(0, 80);
            out.add(product(shini.adminToken(), name, "88" + prefix.length() + String.format("%06d", i), 3, 5, "Dairy Co", 1.0, 30));
        }
        return out;
    }

    @Test
    void anOrderWhoseMessageCannotFitIsNotApprovedAndTellsWhatToRemove() throws Exception {
        shiniData();
        List<Long> ps = longProducts(30, "Long product ");
        long[] pairs = new long[ps.size() * 2];
        for (int i = 0; i < ps.size(); i++) {
            pairs[2 * i] = ps.get(i);
            pairs[2 * i + 1] = 2;
        }
        long id = createOk(shini.adminToken(), dairy, "MANUAL", pairs);
        MvcResult view = expectOk(call("GET", P + id, shini.adminToken(), null));
        assertThat((List<Object>) read(view, "$.approvalBlockers")).containsExactly("MESSAGE_TOO_LONG");
        assertThat((Boolean) read(view, "$.messageLength.ar.alwaysFits")).isFalse();
        assertThat((Integer) read(view, "$.messageLength.ar.limit")).isEqualTo(2000);
        assertThat((Integer) read(view, "$.messageLength.ar.maxPossibleLength"))
                .isGreaterThan((Integer) read(view, "$.messageLength.ar.length"));
        assertThat((List<Object>) read(view, "$.messageLength.ar.longestLines")).hasSize(5);
        int remove = Math.max((Integer) read(view, "$.messageLength.ar.minLinesToRemove"),
                0);
        int removeEn = read(view, "$.messageLength.en.minLinesToRemove");
        assertThat(remove).isPositive();

        MvcResult refused = post(id + "/approve", shini.adminToken(), "{\"version\":0}");
        assertThat(code(refused)).isEqualTo("MESSAGE_TOO_LONG");
        assertThat((Integer) read(refused, "$.details.messageLength.ar.minLinesToRemove")).isEqualTo(remove);
        assertThat(state(id)).isEqualTo("PENDING_APPROVAL");
        assertThat(version(id)).isZero();
        assertThat(eventTypes(id)).containsExactly("CREATED");

        // recovery without cancelling: edit the pending order (the removed products are released)
        int keep = ps.size() - Math.min(remove, removeEn);
        StringBuilder edit = new StringBuilder("{\"version\":0,\"items\":[");
        for (int i = 0; i < keep; i++) edit.append(i == 0 ? "" : ",").append("{\"productId\":").append(ps.get(i)).append(",\"quantity\":2}");
        edit.append("]}");
        expectOk(call("PUT", P + id, shini.adminToken(), edit.toString()));
        assertThat(reservedBy(ps.get(ps.size() - 1))).isNull();
        expectOk(post(id + "/approve", shini.adminToken(), "{\"version\":1}"));
        MvcResult lang = expectOk(call("GET", P + id, shini.adminToken(), null));
        String fitting = (Boolean) read(lang, "$.messageLength.ar.alwaysFits") ? "ar" : "en";
        expectOk(generate(id, fitting));
        assertThat(WhatsappMessageBuilder.length(messageText(id))).isLessThanOrEqualTo(2000);
        assertThat(messageText(id)).as("nothing was cut").contains("Long product " + (keep - 1));
        assertThat(db.queryForObject("SELECT length(message_text) FROM purchase_orders WHERE order_id = ?", Integer.class, id))
                .isEqualTo(WhatsappMessageBuilder.length(messageText(id)));
    }

    @Test
    void anOlderApprovedOrderThatIsTooLongGetsAClearErrorAndKeepsItsState() throws Exception {
        shiniData();
        List<Long> ps = longProducts(30, "Legacy item ");
        long[] pairs = new long[ps.size() * 2];
        for (int i = 0; i < ps.size(); i++) {
            pairs[2 * i] = ps.get(i);
            pairs[2 * i + 1] = 1;
        }
        long id = createOk(shini.adminToken(), dairy, "MANUAL", pairs);
        // approved before Phase 3 had the length check (written in the TEST database)
        db.update("UPDATE purchase_orders SET status = 'APPROVED', approved_by = created_by, approved_at = created_at, "
                + "version = version + 1 WHERE order_id = ?", id);
        MvcResult p = preview(id, "ar");
        assertThat(code(p)).isEqualTo("MESSAGE_TOO_LONG");
        assertThat((Integer) read(p, "$.details.messageLength.ar.length")).isGreaterThan(2000);
        assertThat(code(post(id + "/message", shini.adminToken(), "{\"version\":1,\"language\":\"ar\",\"previewToken\":\""
                + "0".repeat(64) + "\"}"))).isEqualTo("MESSAGE_TOO_LONG");
        assertThat(state(id)).isEqualTo("APPROVED");
        assertThat(version(id)).isEqualTo(1);
        assertThat(messageText(id)).isNull();
        assertThat(reservedBy(ps.get(0))).isEqualTo(id);
        // it can be canceled before contact (no message exists): no warning
        expectOk(post(id + "/cancel", shini.adminToken(), "{\"version\":1,\"reason\":\"Split into two orders\"}"));
        assertThat(candidateCautions(ps.get(0))).isEmpty();
    }

    // ── isolation, roles ────────────────────────────────────────────────────────────

    @Test
    void anotherStoreAndOtherRolesCannotSeeOrChangeAnyCommunication() throws Exception {
        shiniData();
        long id = generated(labneh, 5);
        String text = messageText(id);
        long neshanDairy = supplier(neshan.adminToken(), "Dairy Co", "970599000009");
        long neshanLabneh = product(neshan.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long theirs = createOk(neshan.adminToken(), neshanDairy, "MANUAL", neshanLabneh, 5);
        String sys = sysadminToken();
        String[][] calls = {{"GET", P + id + "/message-preview", null}, {"GET", P + id + "/message", null},
                {"POST", P + id + "/message", "{\"version\":2,\"language\":\"ar\",\"previewToken\":\"" + "0".repeat(64) + "\"}"},
                {"POST", P + id + "/message-copied", "{\"clientActionId\":\"" + action() + "\"}"},
                {"POST", P + id + "/whatsapp-open-requested", "{\"clientActionId\":\"" + action() + "\",\"confirmed\":true}"},
                {"POST", P + id + "/whatsapp-open-result", "{\"clientActionId\":\"" + action() + "\",\"result\":\"OPENED\"}"},
                {"POST", P + id + "/mark-sent", "{\"version\":2,\"confirmSentInWhatsapp\":true}"},
                {"POST", P + id + "/supplier-confirmation", "{\"version\":2,\"confirmSupplierConfirmed\":true}"},
                {"POST", P + id + "/close", "{\"version\":2,\"confirmNoReceiving\":true}"},
                {"POST", P + id + "/cancel", "{\"version\":2,\"reason\":\"Hacked\",\"contactStatus\":\"UNKNOWN\"}"},
                {"POST", P + id + "/supplier-cancellation-recorded",
                        "{\"confirmSupplierAccepted\":true,\"channel\":\"PHONE\",\"note\":\"Hacked it\"}"},
                {"GET", P + id + "/events", null}, {"GET", P + id, null}};
        for (String[] c : calls) {
            MvcResult other = call(c[0], c[1], neshan.adminToken(), c[2]);
            assertThat(status(other)).as("other store " + c[1]).isEqualTo(404);
            assertThat(body(other)).doesNotContain("970599000001", "Labneh", text.substring(0, 20));
            assertThat(status(call(c[0], c[1], shini.cashierToken(), c[2]))).as("cashier " + c[1]).isEqualTo(403);
            assertThat(status(call(c[0], c[1], sys, c[2]))).as("sysadmin " + c[1]).isEqualTo(403);
            assertThat(status(call(c[0], c[1], null, c[2]))).as("no login " + c[1]).isEqualTo(401);
            assertThat(status(call(c[0], c[1].replace(P + id, P + theirs), shini.adminToken(), c[2])))
                    .as("Shini admin on Neshan " + c[1]).isEqualTo(404);
        }
        assertThat(state(id)).isEqualTo("MESSAGE_GENERATED");
        assertThat(version(id)).isEqualTo(2);
        assertThat(eventTypes(id)).containsExactly("CREATED", "APPROVED", "MESSAGE_GENERATED");
        assertThat(state(theirs)).isEqualTo("PENDING_APPROVAL");
        assertThat(events(theirs)).isEqualTo(1);
    }

    // ── nothing financial, stock or store-related changes ───────────────────────────

    @Test
    void theWholeCommunicationWorkflowNeverChangesStockMoneyReportsOrStoreSettings() throws Exception {
        shiniData();
        long cream = product(shini.adminToken(), "Cream", "77777777", 3, 5, "Dairy Co", 4.0, 30);
        for (int d = 0; d <= 13; d++) sold(shini.storeId(), labneh, 2, d);
        long line = sold(shini.storeId(), milk, 5, 2);
        returned(shini.storeId(), milk, line, 1, 1);
        guardian(shini.adminToken(), true);
        Map<String, Object> before = financialSnapshot();
        List<Map<String, Object>> stores = storesWithoutCounter();
        long counter = counter(shini.storeId());
        long activity = db.queryForObject("SELECT COUNT(*) FROM activity_log", Long.class);
        String summary = body(call("GET", "/api/reports/summary?from=2026-01-01&to=2030-12-31", shini.adminToken(), null));
        String dashboard = body(call("GET", "/api/guardian/dashboard", shini.adminToken(), null))
                .replaceAll("\"generatedAt\":\"[^\"]*\"", "");

        long closed = confirmed(labneh, 29);
        expectOk(post(closed + "/message-copied", shini.adminToken(), "{\"clientActionId\":\"" + action() + "\"}"));
        expectOk(post(closed + "/close", shini.adminToken(), "{\"version\":4,\"confirmNoReceiving\":true}"));
        long after = sent(milk, 10);
        expectOk(post(after + "/cancel", shini.adminToken(), "{\"version\":3,\"reason\":\"Too slow\",\"acknowledgeSupplierNotNotified\":true}"));
        expectOk(post(after + "/supplier-cancellation-recorded", shini.adminToken(),
                "{\"confirmSupplierAccepted\":true,\"channel\":\"WHATSAPP\",\"note\":\"Supplier said OK\"}"));
        long unknown = generated(cream, 3);
        String open = action();
        expectOk(post(unknown + "/whatsapp-open-requested", shini.adminToken(), "{\"clientActionId\":\"" + open + "\",\"confirmed\":true}"));
        expectOk(post(unknown + "/whatsapp-open-result", shini.adminToken(), "{\"clientActionId\":\"" + open + "\",\"result\":\"FAILED\"}"));
        expectOk(post(unknown + "/cancel", shini.adminToken(), "{\"version\":2,\"reason\":\"Changed\",\"contactStatus\":\"UNKNOWN\"}"));

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(storesWithoutCounter()).as("the write lock changes no store setting or timestamp").isEqualTo(stores);
        assertThat(counter(shini.storeId())).as("only the three new order numbers were taken").isEqualTo(counter + 3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activity_log", Long.class)).isEqualTo(activity);
        assertThat(body(call("GET", "/api/reports/summary?from=2026-01-01&to=2030-12-31", shini.adminToken(), null)))
                .isEqualTo(summary);
        assertThat(body(call("GET", "/api/guardian/dashboard", shini.adminToken(), null))
                .replaceAll("\"generatedAt\":\"[^\"]*\"", "")).as("Guardian indicators unchanged").isEqualTo(dashboard);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE open_product_id IS NOT NULL", Long.class))
                .isZero();
    }

    private List<Map<String, Object>> storesWithoutCounter() {
        List<Map<String, Object>> rows = db.queryForList("SELECT * FROM stores ORDER BY store_id");
        rows.forEach(r -> r.remove("next_purchase_order_number"));
        return rows;
    }
}
