package com.clixsell.pos.purchase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Purchasing Phase 2 through the real application (real HTTP requests, real SQLite test
 * database): recommendations, manual candidates, create / edit / approve / reject / cancel,
 * idempotency, cautions, history, roles and two-store isolation. After every test the
 * database is checked (reservations, store ownership, foreign keys).
 */
class PurchaseApiTest extends PurchaseApiTestBase {

    @AfterEach
    void databaseStaysConsistent() {
        assertDatabaseConsistent();
    }

    // ── recommendations ─────────────────────────────────────────────────────────────

    @Test
    void guardianOffGivesTheDisabledAnswerAndNothingIsCalculated() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        product(shini.adminToken(), "Labneh", "11111111", 0, 5, "Dairy Co", 9.5, 30);
        MvcResult r = call("GET", "/api/purchase-recommendations", shini.adminToken(), null);
        assertThat(status(r)).isEqualTo(200);
        assertThat((Boolean) read(r, "$.guardianEnabled")).isFalse();
        assertThat((List<?>) read(r, "$.suppliers")).isEmpty();
        assertThat((String) read(r, "$.engine")).isEqualTo("RULES");
        // manual purchasing stays available
        assertThat(status(call("GET", "/api/purchase-candidates?supplierId=" + dairy, shini.adminToken(), null))).isEqualTo(200);
        assertThat(status(create(shini.adminToken(), dairy, "MANUAL", db.queryForObject(
                "SELECT product_id FROM products WHERE barcode='11111111'", Long.class), 10))).isEqualTo(201);
    }

    @Test
    void recommendationsFollowTheApprovedRulesWithRealSalesAndReturns() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        // E1: qty 4, min 5, 28 net units in the last 14 days → URGENT, SALES_RATE, suggested 29
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 4, 5, "Dairy Co", 9.5, 30);
        for (int d = 1; d <= 13; d++) sold(shini.storeId(), labneh, 2, d);
        sold(shini.storeId(), labneh, 2, 0);
        // E2: qty 10, min 3, 21 net → SOON (6.7 days), suggested 14
        long milk = product(shini.adminToken(), "Milk", "22222222", 10, 3, "Dairy Co", 3.2, 30);
        sold(shini.storeId(), milk, 21, 5);
        // E5: sold 10, returned 8 → net 2 → MIN_STOCK_ONLY: qty 3, min 4 → 8 − 3 = 5
        long cheese = product(shini.adminToken(), "Cheese", "33333333", 3, 4, "Dairy Co", null, 30);
        long line = sold(shini.storeId(), cheese, 10, 6);
        returned(shini.storeId(), cheese, line, 8, 5);
        // E3: new product (5 observed days) with 4 units sold → not enough data: 2 × 6 − 2 = 10
        long yogurt = product(shini.adminToken(), "Yogurt", "44444444", 2, 6, "Dairy Co", 2.0, 4);
        sold(shini.storeId(), yogurt, 4, 1);
        // E4: out of stock, no sales, minimum 0 → MANUAL, no quantity
        long eggs = product(shini.adminToken(), "Eggs", "55555555", 0, 0, "Dairy Co", null, 30);
        // E7: plenty of stock → not recommended
        product(shini.adminToken(), "Butter", "66666666", 50, 5, "Dairy Co", 7.0, 30);
        guardian(shini.adminToken(), true);

        MvcResult r = call("GET", "/api/purchase-recommendations", shini.adminToken(), null);
        assertThat(status(r)).isEqualTo(200);
        assertThat((Boolean) read(r, "$.guardianEnabled")).isTrue();
        assertThat(((Number) read(r, "$.horizonDays")).intValue()).isEqualTo(7);
        String base = "$.suppliers[?(@.supplierId==" + dairy + ")].items[?(@.productId==%d)].";
        assertThat((List<Object>) read(r, base.formatted(labneh) + "classification")).containsExactly("URGENT");
        assertThat((List<Object>) read(r, base.formatted(labneh) + "basis")).containsExactly("SALES_RATE");
        assertThat((List<Object>) read(r, base.formatted(labneh) + "suggestedQuantity")).containsExactly(29);
        assertThat((List<Object>) read(r, base.formatted(labneh) + "expectedUnitCost")).containsExactly(9.5);
        assertThat((List<Object>) read(r, base.formatted(milk) + "classification")).containsExactly("SOON");
        assertThat((List<Object>) read(r, base.formatted(milk) + "suggestedQuantity")).containsExactly(14);
        assertThat((List<Object>) read(r, base.formatted(cheese) + "basis")).containsExactly("MIN_STOCK_ONLY");
        assertThat((List<Object>) read(r, base.formatted(cheese) + "netUnits")).containsExactly(2);
        assertThat((List<Object>) read(r, base.formatted(cheese) + "suggestedQuantity")).containsExactly(5);
        assertThat((List<Object>) read(r, base.formatted(cheese) + "expectedUnitCost")).containsExactly((Object) null);
        assertThat((List<Object>) read(r, base.formatted(yogurt) + "suggestedQuantity")).containsExactly(10);
        assertThat((List<Object>) read(r, base.formatted(eggs) + "basis")).containsExactly("MANUAL");
        assertThat((List<Object>) read(r, base.formatted(eggs) + "suggestedQuantity")).containsExactly((Object) null);
        assertThat((List<Object>) read(r, "$..items[?(@.name=='Butter')]")).isEmpty();
        assertThat(body(r)).doesNotContain("confidence");
        // Guardian's own 3-day alert still counts only the URGENT product (unchanged)
        MvcResult dash = call("GET", "/api/guardian/dashboard", shini.adminToken(), null);
        assertThat(body(dash)).contains("REORDER_SOON");
    }

    @Test
    void openOrdersInactiveSuppliersAndOtherStoresAreShownHonestly() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long old = supplier(shini.adminToken(), "Old Co", null);
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 0, 5, "Dairy Co", 9.5, 30);
        long tea = product(shini.adminToken(), "Tea", "22222222", 1, 4, "Old Co", 5.0, 30);
        call("PUT", "/api/suppliers/" + old + "/status", shini.adminToken(), "{\"active\":false}");
        // Neshan: the same names, its own data
        supplier(neshan.adminToken(), "Dairy Co", null);
        product(neshan.adminToken(), "Labneh", "11111111", 0, 9, "Dairy Co", 1.0, 30);
        guardian(shini.adminToken(), true);
        long order = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 10);
        String number = db.queryForObject("SELECT order_number FROM purchase_orders WHERE order_id=?", String.class, order);

        MvcResult r = call("GET", "/api/purchase-recommendations", shini.adminToken(), null);
        String l = "$..items[?(@.productId==" + labneh + ")].";
        assertThat((List<Object>) read(r, l + "available")).containsExactly(false);
        assertThat((List<Object>) read(r, l + "unavailableReason")).containsExactly("IN_OPEN_ORDER");
        assertThat((List<Object>) read(r, l + "openOrder.orderNumber")).containsExactly(number);
        assertThat((List<Object>) read(r, l + "openOrder.quantity")).containsExactly(10);
        String t = "$..items[?(@.productId==" + tea + ")].";
        assertThat((List<Object>) read(r, t + "unavailableReason")).containsExactly("SUPPLIER_INACTIVE");
        assertThat((List<Object>) read(r, "$.suppliers[?(@.supplierId==" + old + ")].active")).containsExactly(false);
        assertThat(body(r)).doesNotContain("\"minStock\":9"); // nothing from Neshan
        // E12: an inactive supplier's product cannot be ordered
        MvcResult refused = create(shini.adminToken(), old, "MANUAL", tea, 5);
        assertThat(status(refused)).isEqualTo(409);
        assertThat((String) read(refused, "$.code")).isEqualTo("SUPPLIER_INACTIVE");
        assertThat(db.queryForObject("SELECT active FROM suppliers WHERE supplier_id=?", Integer.class, old)).isZero();
    }

    // ── manual candidates ───────────────────────────────────────────────────────────

    @Test
    void candidatesAreFactsOnlyAndStayInsideTheStore() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long empty = supplier(shini.adminToken(), "Empty Co", null);
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 0, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 30, 5, "Dairy Co", null, 30);
        long neshanDairy = supplier(neshan.adminToken(), "Dairy Co", null);
        createOk(shini.adminToken(), dairy, "MANUAL", labneh, 4);

        MvcResult r = call("GET", "/api/purchase-candidates?supplierId=" + dairy, shini.adminToken(), null);
        assertThat(status(r)).isEqualTo(200);
        assertThat((List<Object>) read(r, "$.products[*].productId")).containsExactlyInAnyOrder((int) labneh, (int) milk);
        assertThat(body(r)).doesNotContain("suggestedQuantity").doesNotContain("confidence");
        assertThat((List<Object>) read(r, "$.products[?(@.productId==" + labneh + ")].unavailableReason"))
                .containsExactly("IN_OPEN_ORDER");
        assertThat((List<Object>) read(r, "$.products[?(@.productId==" + milk + ")].stockStatus")).containsExactly("OK");
        assertThat((List<Object>) read(r, "$.products[?(@.productId==" + milk + ")].expectedUnitCost"))
                .containsExactly((Object) null); // unknown cost stays unknown
        MvcResult none = call("GET", "/api/purchase-candidates?supplierId=" + empty, shini.adminToken(), null);
        assertThat((List<?>) read(none, "$.products")).isEmpty();
        assertThat(status(call("GET", "/api/purchase-candidates?supplierId=" + neshanDairy, shini.adminToken(), null)))
                .isEqualTo(404);
        assertThat(status(call("GET", "/api/purchase-candidates?supplierId=999999", shini.adminToken(), null))).isEqualTo(404);
        assertThat(status(call("GET", "/api/purchase-candidates", shini.adminToken(), null))).isEqualTo(400);
        assertThat(status(call("GET", "/api/purchase-candidates?supplierId=abc", shini.adminToken(), null))).isEqualTo(400);
        call("PUT", "/api/suppliers/" + dairy + "/status", shini.adminToken(), "{\"active\":false}");
        MvcResult inactive = call("GET", "/api/purchase-candidates?supplierId=" + dairy, shini.adminToken(), null);
        assertThat((Boolean) read(inactive, "$.active")).isFalse();
        assertThat((List<Object>) read(inactive, "$.products[*].available")).containsOnly(false);
    }

    // ── creation ────────────────────────────────────────────────────────────────────

    @Test
    void aManualOrderIsCreatedPendingWithServerSnapshotsAndOneEvent() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 0, 5, "Dairy Co", null, 30);
        Map<String, Object> before = financialSnapshot();
        String key = UUID.randomUUID().toString();
        MvcResult r = call("POST", "/api/purchase-orders", shini.adminToken(),
                orderJson(key, dairy, "MANUAL", "  First order  ", items(labneh, 12, milk, 6), null));
        assertThat(status(r)).isEqualTo(201);
        long id = ((Number) read(r, "$.orderId")).longValue();
        assertThat((String) read(r, "$.orderNumber")).isEqualTo("PO-000001");
        assertThat((String) read(r, "$.status")).isEqualTo("PENDING_APPROVAL");
        assertThat((String) read(r, "$.note")).isEqualTo("First order");
        assertThat((Integer) read(r, "$.version")).isZero();
        assertThat((Boolean) read(r, "$.replayed")).isFalse();
        assertThat((Object) read(r, "$.expectedTotalCost")).isNull(); // Milk has no cost → total unknown, not 0
        assertThat((List<Object>) read(r, "$.allowedActions")).containsExactly("EDIT", "APPROVE", "REJECT", "CANCEL");
        assertThat((String) read(r, "$.createdBy.fullName")).isEqualTo("Test User");

        assertThat(db.queryForObject("SELECT expected_unit_cost FROM purchase_order_items WHERE product_id = ?", Double.class,
                labneh)).isEqualTo(9.5);
        assertThat(db.queryForObject("SELECT expected_unit_cost FROM purchase_order_items WHERE product_id = ?", Double.class,
                milk)).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE order_id=? AND suggested_quantity IS NULL "
                + "AND basis='MANUAL'", Long.class, id)).isEqualTo(2);
        assertThat(reservedBy(labneh)).isEqualTo(id);
        assertThat(reservedBy(milk)).isEqualTo(id);
        assertThat(eventTypes(id)).containsExactly("CREATED");
        assertThat(db.queryForObject("SELECT user_id FROM purchase_order_events WHERE order_id=?", Long.class, id))
                .isEqualTo(adminId("shini.admin"));
        assertThat(db.queryForObject("SELECT created_by FROM purchase_orders WHERE order_id=?", Long.class, id))
                .isEqualTo(adminId("shini.admin"));
        assertThat(counter(shini.storeId())).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE message_text IS NOT NULL", Long.class)).isZero();
        assertThat(financialSnapshot()).as("stock, prices, costs, sales untouched").isEqualTo(before);
    }

    @Test
    void guardianOrdersAreCheckedOnTheServer() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 4, 5, "Dairy Co", 9.5, 30);
        for (int d = 0; d <= 13; d++) sold(shini.storeId(), labneh, 2, d);
        long butter = product(shini.adminToken(), "Butter", "22222222", 50, 5, "Dairy Co", 7.0, 30);
        // Guardian OFF → GUARDIAN_DISABLED, nothing saved, no number taken
        MvcResult off = create(shini.adminToken(), dairy, "GUARDIAN", labneh, 29);
        assertThat(status(off)).isEqualTo(409);
        assertThat((String) read(off, "$.code")).isEqualTo("GUARDIAN_DISABLED");
        assertThat(counter(shini.storeId())).isEqualTo(1);
        guardian(shini.adminToken(), true);
        // a product that is not a current recommendation cannot claim source GUARDIAN
        MvcResult notRec = create(shini.adminToken(), dairy, "GUARDIAN", butter, 5);
        assertThat(status(notRec)).isEqualTo(409);
        assertThat((String) read(notRec, "$.code")).isEqualTo("NOT_A_CURRENT_RECOMMENDATION");
        // a real recommendation (the manager may change the quantity)
        MvcResult ok = create(shini.adminToken(), dairy, "GUARDIAN", labneh, 30);
        assertThat(status(ok)).isEqualTo(201);
        assertThat((String) read(ok, "$.source")).isEqualTo("GUARDIAN");
        assertThat((Integer) read(ok, "$.items[0].suggestedQuantity")).isEqualTo(29);
        assertThat((String) read(ok, "$.items[0].basis")).isEqualTo("SALES_RATE");
        assertThat((Boolean) read(ok, "$.items[0].calculation.managerChangedQuantity")).isTrue();
        assertThat((String) read(ok, "$.items[0].calculation.classification")).isEqualTo("URGENT");
        // Guardian turned off later: the Guardian order is kept and can still be approved
        guardian(shini.adminToken(), false);
        long id = ((Number) read(ok, "$.orderId")).longValue();
        assertThat(status(call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":0}")))
                .isEqualTo(200);
    }

    @Test
    void invalidCreateRequestsAreRefusedAndLeaveNothing() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long bakery = supplier(shini.adminToken(), "Bakery Co", null);
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long bread = product(shini.adminToken(), "Bread", "22222222", 3, 5, "Bakery Co", 1.0, 30);
        long neshanDairy = supplier(neshan.adminToken(), "Dairy Co", null);
        long neshanLabneh = product(neshan.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        String good = orderJson(UUID.randomUUID().toString(), dairy, "MANUAL", null, items(labneh, 5), null);

        for (String field : List.of("storeId", "createdBy", "status", "orderNumber", "suggestedQuantity",
                "expectedUnitCost", "unitCost", "price")) {
            String bad = good.substring(0, good.length() - 1) + ",\"" + field + "\":1}";
            MvcResult r = call("POST", "/api/purchase-orders", shini.adminToken(), bad);
            assertThat(status(r)).as(field).isEqualTo(400);
            assertThat((String) read(r, "$.code")).as(field).isEqualTo("UNKNOWN_FIELD");
        }
        MvcResult inItem = call("POST", "/api/purchase-orders", shini.adminToken(),
                good.replace("\"quantity\":5}", "\"quantity\":5,\"price\":2}"));
        assertThat((String) read(inItem, "$.code")).isEqualTo("UNKNOWN_FIELD");

        record Case(String name, String json, int status, String code) { }
        String k = UUID.randomUUID().toString();
        List<Case> cases = List.of(
                new Case("not a UUID", orderJson("abc", dairy, "MANUAL", null, items(labneh, 5), null), 400, "VALIDATION_FAILED"),
                new Case("unknown source", orderJson(k, dairy, "AI", null, items(labneh, 5), null), 400, "VALIDATION_FAILED"),
                new Case("no items", orderJson(k, dairy, "MANUAL", null, items(), null), 400, "VALIDATION_FAILED"),
                new Case("quantity 0", orderJson(k, dairy, "MANUAL", null, items(labneh, 0), null), 400, "VALIDATION_FAILED"),
                new Case("quantity −3", orderJson(k, dairy, "MANUAL", null, items(labneh, -3), null), 400, "VALIDATION_FAILED"),
                new Case("quantity 100001", orderJson(k, dairy, "MANUAL", null, items(labneh, 100001), null), 400, "VALIDATION_FAILED"),
                new Case("quantity 2.5", good.replace("\"quantity\":5", "\"quantity\":2.5"), 400, "VALIDATION_FAILED"),
                new Case("same product twice", orderJson(k, dairy, "MANUAL", null, items(labneh, 5, labneh, 2), null), 400, "VALIDATION_FAILED"),
                new Case("no supplier", orderJson(k, 999999, "MANUAL", null, items(labneh, 5), null), 404, "SUPPLIER_NOT_FOUND"),
                new Case("other store's supplier", orderJson(k, neshanDairy, "MANUAL", null, items(neshanLabneh, 5), null), 404, "SUPPLIER_NOT_FOUND"),
                new Case("other store's product", orderJson(k, dairy, "MANUAL", null, items(neshanLabneh, 5), null), 404, "PRODUCT_NOT_FOUND"),
                new Case("other supplier's product", orderJson(k, dairy, "MANUAL", null, items(bread, 5), null), 400, "PRODUCT_NOT_FROM_SUPPLIER"),
                new Case("not an object", "[1,2]", 400, "BAD_REQUEST"));
        for (Case c : cases) {
            MvcResult r = call("POST", "/api/purchase-orders", shini.adminToken(), c.json());
            assertThat(status(r)).as(c.name()).isEqualTo(c.status());
            assertThat((String) read(r, "$.code")).as(c.name()).isEqualTo(c.code());
        }
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 51; i++) many.append(i == 1 ? "" : ",").append("{\"productId\":").append(i).append(",\"quantity\":1}");
        MvcResult tooMany = call("POST", "/api/purchase-orders", shini.adminToken(), "{\"clientRequestId\":\"" + k
                + "\",\"supplierId\":" + dairy + ",\"source\":\"MANUAL\",\"items\":[" + many + "]}");
        assertThat(status(tooMany)).isEqualTo(400);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_events", Long.class)).isZero();
        assertThat(counter(shini.storeId())).as("no order number consumed").isEqualTo(1);
        assertThat(bakery).isPositive();
    }

    @Test
    void aProductInAnOpenOrderCannotBeOrderedTwiceUntilReleased() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long first = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);
        String number = db.queryForObject("SELECT order_number FROM purchase_orders WHERE order_id=?", String.class, first);
        MvcResult dup = create(shini.adminToken(), dairy, "MANUAL", milk, 2, labneh, 7);
        assertThat(status(dup)).isEqualTo(409);
        assertThat((String) read(dup, "$.code")).isEqualTo("PRODUCT_IN_OPEN_ORDER");
        assertThat((String) read(dup, "$.details.openOrders[0].orderNumber")).isEqualTo(number);
        assertThat(reservedBy(milk)).as("nothing of the refused order was saved").isNull();
        // Neshan's own "Labneh" is a different product: not blocked
        supplier(neshan.adminToken(), "Dairy Co", null);
        long nLabneh = product(neshan.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long nDairy = db.queryForObject("SELECT supplier_id FROM suppliers WHERE store_id=? AND name='Dairy Co'", Long.class,
                neshan.storeId());
        assertThat(status(create(neshan.adminToken(), nDairy, "MANUAL", nLabneh, 1))).isEqualTo(201);
        // releasing (cancel) allows ordering again
        call("POST", "/api/purchase-orders/" + first + "/cancel", shini.adminToken(), "{\"version\":0,\"reason\":\"Not needed\"}");
        assertThat(reservedBy(labneh)).isNull();
        assertThat(status(create(shini.adminToken(), dairy, "MANUAL", labneh, 7))).isEqualTo(201);
    }

    // ── idempotency ─────────────────────────────────────────────────────────────────

    @Test
    void theSameRequestIdIsSafeToRetryAndCannotBeReusedForAnotherRequest() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        String key = UUID.randomUUID().toString();
        String json = orderJson(key, dairy, "MANUAL", "Weekly", items(labneh, 5, milk, 2), null);
        MvcResult first = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        long id = ((Number) read(first, "$.orderId")).longValue();

        // I1: identical retry (items in another order) → the same order, replayed, nothing new
        String same = orderJson(key, dairy, "MANUAL", "Weekly", items(milk, 2, labneh, 5), null);
        MvcResult again = call("POST", "/api/purchase-orders", shini.adminToken(), same);
        assertThat(status(again)).isEqualTo(200);
        assertThat(((Number) read(again, "$.orderId")).longValue()).isEqualTo(id);
        assertThat((Boolean) read(again, "$.replayed")).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(1);
        assertThat(events(id)).isEqualTo(1);
        assertThat(counter(shini.storeId())).isEqualTo(2);

        // I2: the same id with different content → 409, never the old order
        for (String changed : List.of(orderJson(key, dairy, "MANUAL", "Weekly", items(labneh, 6, milk, 2), null),
                orderJson(key, dairy, "MANUAL", "Other note", items(labneh, 5, milk, 2), null),
                orderJson(key, dairy, "MANUAL", "Weekly", items(labneh, 5), null),
                orderJson(key, dairy, "MANUAL", "Weekly", items(labneh, 5, milk, 2), List.of(new long[]{labneh, 1})),
                orderJson(key, dairy, "GUARDIAN", "Weekly", items(labneh, 5, milk, 2), null))) {
            MvcResult r = call("POST", "/api/purchase-orders", shini.adminToken(), changed);
            assertThat(status(r)).isEqualTo(409);
            assertThat((String) read(r, "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            assertThat(body(r)).doesNotContain("\"orderId\"");
        }
        // I3: the same id and content from another Store Admin of the store → 409
        MvcResult other = call("POST", "/api/purchase-orders", shiniAdmin2, json);
        assertThat((String) read(other, "$.code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        // I5: a replay after approval returns the order as it is now
        call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":0}");
        MvcResult late = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        assertThat((String) read(late, "$.status")).isEqualTo("APPROVED");
        assertThat(events(id)).isEqualTo(2);
        // the same id in another store is independent
        long nDairy = supplier(neshan.adminToken(), "Dairy Co", null);
        long nLabneh = product(neshan.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        MvcResult n = call("POST", "/api/purchase-orders", neshan.adminToken(),
                orderJson(key, nDairy, "MANUAL", "Weekly", items(nLabneh, 5), null));
        assertThat(status(n)).isEqualTo(201);
        assertThat((String) read(n, "$.orderNumber")).isEqualTo("PO-000001");
    }

    @Test
    void aFailedRequestStoresNothingAndTheSameIdCanBeUsedAfterwards() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long first = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);
        String key = UUID.randomUUID().toString();
        String json = orderJson(key, dairy, "MANUAL", null, items(labneh, 7), null);
        assertThat(status(call("POST", "/api/purchase-orders", shini.adminToken(), json))).isEqualTo(409); // reserved
        assertThat(counter(shini.storeId())).isEqualTo(2);
        call("POST", "/api/purchase-orders/" + first + "/reject", shini.adminToken(), "{\"version\":0,\"reason\":\"Too many\"}");
        MvcResult retry = call("POST", "/api/purchase-orders", shini.adminToken(), json);
        assertThat(status(retry)).isEqualTo(201);
        assertThat((String) read(retry, "$.orderNumber")).isEqualTo("PO-000002"); // no gap
    }

    // ── cautions ────────────────────────────────────────────────────────────────────

    @Test
    void recentlyClosedAndCanceledAfterContactOrdersNeedAnExplicitAcknowledgment() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long cheese = product(shini.adminToken(), "Cheese", "33333333", 3, 5, "Dairy Co", 3.0, 30);
        long closed = historicOrder(shini.storeId(), dairy, "shini.admin", labneh, 20, "CLOSED", 3);
        long afterContact = historicOrder(shini.storeId(), dairy, "shini.admin", milk, 8, "CANCELED", 10);
        historicOrder(shini.storeId(), dairy, "shini.admin", cheese, 8, "CLOSED", 15); // older than 14 days: no caution

        MvcResult r = create(shini.adminToken(), dairy, "MANUAL", labneh, 5, milk, 4, cheese, 2);
        assertThat(status(r)).isEqualTo(409);
        assertThat((String) read(r, "$.code")).isEqualTo("REORDER_CAUTION_ACK_REQUIRED");
        assertThat((List<Object>) read(r, "$.details.cautions[*].type"))
                .containsExactlyInAnyOrder("RECENTLY_CLOSED", "CANCELED_AFTER_CONTACT");
        assertThat((List<Object>) read(r, "$.details.cautions[*].orderId"))
                .containsExactlyInAnyOrder((int) closed, (int) afterContact);
        // one acknowledgment is not enough
        MvcResult partial = call("POST", "/api/purchase-orders", shini.adminToken(), orderJson(UUID.randomUUID().toString(),
                dairy, "MANUAL", null, items(labneh, 5, milk, 4, cheese, 2), List.<long[]>of(new long[]{labneh, closed})));
        assertThat((String) read(partial, "$.code")).isEqualTo("REORDER_CAUTION_ACK_REQUIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE order_number LIKE 'PO-0%'", Long.class)).isZero();

        MvcResult ok = call("POST", "/api/purchase-orders", shini.adminToken(), orderJson(UUID.randomUUID().toString(),
                dairy, "MANUAL", null, items(labneh, 5, milk, 4, cheese, 2),
                List.of(new long[]{labneh, closed}, new long[]{milk, afterContact})));
        assertThat(status(ok)).isEqualTo(201);
        long id = ((Number) read(ok, "$.orderId")).longValue();
        assertThat(db.queryForObject("SELECT details FROM purchase_order_events WHERE order_id=?", String.class, id))
                .contains("cautionsAcknowledged").contains("RECENTLY_CLOSED").contains("CANCELED_AFTER_CONTACT");
        assertThat(db.queryForObject("SELECT calc_json FROM purchase_order_items WHERE order_id=? AND product_id=?",
                String.class, id, labneh)).contains("cautionsAcknowledged").contains(String.valueOf(closed));
    }

    @Test
    void aRecordedSupplierAcceptanceOrAnOldCancellationNeedsNoAcknowledgment() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long accepted = historicOrder(shini.storeId(), dairy, "shini.admin", labneh, 8, "CANCELED", 5);
        db.update("INSERT INTO purchase_order_events (order_id, store_id, user_id, event_type, created_at) VALUES "
                + "(?, ?, ?, 'SUPPLIER_CANCELLATION_RECORDED', '2026-11-20T10:00:00')", accepted, shini.storeId(),
                adminId("shini.admin"));
        historicOrder(shini.storeId(), dairy, "shini.admin", milk, 8, "CANCELED", 31);
        assertThat(status(create(shini.adminToken(), dairy, "MANUAL", labneh, 5, milk, 2))).isEqualTo(201);
    }

    // ── edit ────────────────────────────────────────────────────────────────────────

    @Test
    void editingAPendingOrderChangesLinesAndReservationsTogether() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long cheese = product(shini.adminToken(), "Cheese", "33333333", 3, 5, "Dairy Co", 4.0, 30);
        long id = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5, milk, 2);

        // nothing changed → no event, same version
        MvcResult same = call("PUT", "/api/purchase-orders/" + id, shini.adminToken(),
                "{\"version\":0,\"items\":[{\"productId\":" + labneh + ",\"quantity\":5},{\"productId\":" + milk + ",\"quantity\":2}]}");
        assertThat(status(same)).isEqualTo(200);
        assertThat(version(id)).isZero();
        assertThat(events(id)).isEqualTo(1);

        // change Labneh, remove Milk, add Cheese, add a note
        MvcResult r = call("PUT", "/api/purchase-orders/" + id, shini.adminToken(),
                "{\"version\":0,\"note\":\"Updated\",\"items\":[{\"productId\":" + labneh + ",\"quantity\":9},"
                        + "{\"productId\":" + cheese + ",\"quantity\":4}]}");
        assertThat(status(r)).isEqualTo(200);
        assertThat((Integer) read(r, "$.version")).isEqualTo(1);
        assertThat(reservedBy(milk)).isNull();
        assertThat(reservedBy(cheese)).isEqualTo(id);
        assertThat(db.queryForObject("SELECT quantity FROM purchase_order_items WHERE order_id=? AND product_id=?",
                Integer.class, id, labneh)).isEqualTo(9);
        assertThat(eventTypes(id)).containsExactly("CREATED", "EDITED");
        String details = db.queryForObject("SELECT details FROM purchase_order_events WHERE order_id=? AND event_type='EDITED'",
                String.class, id);
        assertThat(details).contains("\"quantityChanged\"").contains("\"from\":5").contains("\"to\":9")
                .contains("Milk").contains("Cheese").contains("Updated");

        // stale version → 409, nothing changes
        MvcResult stale = call("PUT", "/api/purchase-orders/" + id, shini.adminToken(),
                "{\"version\":0,\"items\":[{\"productId\":" + labneh + ",\"quantity\":1}]}");
        assertThat((String) read(stale, "$.code")).isEqualTo("ORDER_CHANGED");
        // an edit that adds a product reserved elsewhere fails as a whole
        long other = createOk(shini.adminToken(), dairy, "MANUAL", milk, 3);
        MvcResult blocked = call("PUT", "/api/purchase-orders/" + id, shini.adminToken(),
                "{\"version\":1,\"items\":[{\"productId\":" + milk + ",\"quantity\":1}]}");
        assertThat((String) read(blocked, "$.code")).isEqualTo("PRODUCT_IN_OPEN_ORDER");
        assertThat(reservedBy(labneh)).as("the removal of Labneh was rolled back").isEqualTo(id);
        assertThat(reservedBy(cheese)).isEqualTo(id);
        assertThat(version(id)).isEqualTo(1);
        assertThat(events(id)).isEqualTo(2);
        assertThat(reservedBy(milk)).isEqualTo(other);

        // remove everything → an empty pending order that cannot be approved
        MvcResult empty = call("PUT", "/api/purchase-orders/" + id, shini.adminToken(), "{\"version\":1,\"items\":[]}");
        assertThat((List<?>) read(empty, "$.items")).isEmpty();
        assertThat((List<Object>) read(empty, "$.approvalBlockers")).contains("ORDER_EMPTY");
        MvcResult refused = call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":2}");
        assertThat((String) read(refused, "$.code")).isEqualTo("ORDER_EMPTY");
        // a valid line again → approvable
        MvcResult readd = call("PUT", "/api/purchase-orders/" + id, shini.adminToken(),
                "{\"version\":2,\"items\":[{\"productId\":" + labneh + ",\"quantity\":3}]}");
        assertThat(status(readd)).as(body(readd)).isEqualTo(200);
        // regression: an edit that only changes lines must raise the STORED version (it once stayed 2)
        assertThat(version(id)).isEqualTo(3);
        assertThat((Integer) read(readd, "$.version")).isEqualTo(3);
        assertThat((String) read(call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":2}"),
                "$.code")).as("the version before the edit is stale").isEqualTo("ORDER_CHANGED");
        MvcResult approved = call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(), "{\"version\":3}");
        assertThat(status(approved)).as(body(approved)).isEqualTo(200);
        // after approval no more edits
        MvcResult late = call("PUT", "/api/purchase-orders/" + id, shini.adminToken(),
                "{\"version\":4,\"items\":[{\"productId\":" + labneh + ",\"quantity\":1}]}");
        assertThat((String) read(late, "$.code")).isEqualTo("INVALID_TRANSITION");
        MvcResult unknown = call("PUT", "/api/purchase-orders/" + id, shini.adminToken(), "{\"version\":4,\"supplierId\":5}");
        assertThat((String) read(unknown, "$.code")).isEqualTo("UNKNOWN_FIELD");
    }

    // ── approve / reject / cancel ───────────────────────────────────────────────────

    @Test
    void approvalRecordsTheRealUserOnceAndChecksTheCurrentData() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long bakery = supplier(shini.adminToken(), "Bakery Co", null);
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long id = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5, milk, 2);
        Map<String, Object> before = financialSnapshot();

        // supplier inactive → refused
        call("PUT", "/api/suppliers/" + dairy + "/status", shini.adminToken(), "{\"active\":false}");
        assertThat((String) read(call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(),
                "{\"version\":0}"), "$.code")).isEqualTo("SUPPLIER_INACTIVE");
        call("PUT", "/api/suppliers/" + dairy + "/status", shini.adminToken(), "{\"active\":true}");
        // a product moved to another supplier → refused
        call("PUT", "/api/products/" + milk, shini.adminToken(), productJson("Milk", "22222222", 14, 3, 5, "Bakery Co"));
        assertThat((String) read(call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(),
                "{\"version\":0}"), "$.code")).isEqualTo("PRODUCT_SUPPLIER_CHANGED");
        call("PUT", "/api/products/" + milk, shini.adminToken(), productJson("Milk", "22222222", 14, 3, 5, "Dairy Co"));
        // a product deleted → refused until the line is removed
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/products/" + milk)
                .header("Authorization", bearer(shini.adminToken()))).andReturn();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE order_id=? AND product_id IS NULL",
                Long.class, id)).as("the line keeps its snapshot").isEqualTo(1);
        assertThat((String) read(call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(),
                "{\"version\":0}"), "$.code")).isEqualTo("ITEM_PRODUCT_DELETED");
        call("PUT", "/api/purchase-orders/" + id, shini.adminToken(),
                "{\"version\":0,\"items\":[{\"productId\":" + labneh + ",\"quantity\":5}]}");
        assertThat(events(id)).isEqualTo(2);

        // stale version → refused; the right version → approved by the second admin
        assertThat((String) read(call("POST", "/api/purchase-orders/" + id + "/approve", shiniAdmin2, "{\"version\":0}"),
                "$.code")).isEqualTo("ORDER_CHANGED");
        MvcResult ok = call("POST", "/api/purchase-orders/" + id + "/approve", shiniAdmin2, "{\"version\":1}");
        assertThat(status(ok)).isEqualTo(200);
        assertThat((String) read(ok, "$.status")).isEqualTo("APPROVED");
        assertThat((String) read(ok, "$.approvedBy.fullName")).isEqualTo("Test User");
        // Phase 3: an approved order can get its supplier message prepared (or be canceled)
        assertThat((List<Object>) read(ok, "$.allowedActions")).containsExactly("PREVIEW_MESSAGE", "GENERATE_MESSAGE", "CANCEL");
        assertThat(db.queryForObject("SELECT approved_by FROM purchase_orders WHERE order_id=?", Long.class, id))
                .isEqualTo(adminId("shini.admin2"));
        assertThat(version(id)).isEqualTo(2);
        assertThat(eventTypes(id)).containsExactly("CREATED", "EDITED", "APPROVED");
        assertThat(reservedBy(labneh)).as("still reserved: approved is not final").isEqualTo(id);
        // approving twice → refused, no second event
        assertThat((String) read(call("POST", "/api/purchase-orders/" + id + "/approve", shini.adminToken(),
                "{\"version\":2}"), "$.code")).isEqualTo("INVALID_TRANSITION");
        assertThat(events(id)).isEqualTo(3);
        Map<String, Object> was = new java.util.HashMap<>(before);
        was.remove("products");
        Map<String, Object> after = new java.util.HashMap<>(financialSnapshot());
        after.remove("products"); // Milk was deleted on purpose above
        assertThat(after).isEqualTo(was);
        assertThat(db.queryForObject("SELECT quantity FROM products WHERE product_id=?", Integer.class, labneh))
                .as("approval does not change stock").isEqualTo(3);
        assertThat(bakery).isPositive();
    }

    @Test
    void rejectionNeedsARealReasonAndReleasesEverything() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long id = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5, milk, 2);
        for (String bad : List.of("{\"version\":0}", "{\"version\":0,\"reason\":null}", "{\"version\":0,\"reason\":\"\"}",
                "{\"version\":0,\"reason\":\"    \"}", "{\"version\":0,\"reason\":\"\\t\\n \"}", "{\"version\":0,\"reason\":\"ab\"}",
                "{\"version\":0,\"reason\":\"" + "x".repeat(301) + "\"}", "{\"version\":0,\"reason\":5}",
                "{\"reason\":\"Too expensive\"}", "{\"version\":0,\"reason\":\"Too expensive\",\"status\":\"REJECTED\"}")) {
            MvcResult r = call("POST", "/api/purchase-orders/" + id + "/reject", shini.adminToken(), bad);
            assertThat(status(r)).as(bad).isEqualTo(400);
        }
        assertThat(state(id)).isEqualTo("PENDING_APPROVAL");
        assertThat(events(id)).isEqualTo(1);
        MvcResult ok = call("POST", "/api/purchase-orders/" + id + "/reject", shini.adminToken(),
                "{\"version\":0,\"reason\":\"  Too expensive this week  \"}");
        assertThat(status(ok)).isEqualTo(200);
        assertThat((String) read(ok, "$.rejectionReason")).isEqualTo("Too expensive this week");
        assertThat((List<?>) read(ok, "$.allowedActions")).isEmpty();
        assertThat((List<Object>) read(ok, "$.items[*].reserved")).containsOnly(false);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_order_items WHERE order_id=? AND open_product_id IS NOT NULL",
                Long.class, id)).isZero();
        assertThat(eventTypes(id)).containsExactly("CREATED", "REJECTED");
        assertThat(db.queryForObject("SELECT rejected_by FROM purchase_orders WHERE order_id=?", Long.class, id))
                .isEqualTo(adminId("shini.admin"));
        for (String action : List.of("approve", "reject", "cancel")) {
            MvcResult r = call("POST", "/api/purchase-orders/" + id + "/" + action, shini.adminToken(),
                    "{\"version\":1,\"reason\":\"Again please\"}".replace(",\"reason\":\"Again please\"",
                            action.equals("approve") ? "" : ",\"reason\":\"Again please\""));
            assertThat((String) read(r, "$.code")).as(action).isEqualTo("INVALID_TRANSITION");
        }
        assertThat((String) read(call("PUT", "/api/purchase-orders/" + id, shini.adminToken(),
                "{\"version\":1,\"items\":[]}"), "$.code")).isEqualTo("INVALID_TRANSITION");
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    void cancellationWorksFromPendingAndApprovedOnly() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long pending = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);
        long approved = createOk(shini.adminToken(), dairy, "MANUAL", milk, 5);
        call("POST", "/api/purchase-orders/" + approved + "/approve", shini.adminToken(), "{\"version\":0}");
        assertThat(status(call("POST", "/api/purchase-orders/" + pending + "/cancel", shini.adminToken(),
                "{\"version\":0,\"reason\":\" \"}"))).isEqualTo(400);
        for (long[] o : new long[][]{{pending, 0}, {approved, 1}}) {
            MvcResult r = call("POST", "/api/purchase-orders/" + o[0] + "/cancel", shiniAdmin2,
                    "{\"version\":" + o[1] + ",\"reason\":\"Plan changed\"}");
            assertThat(status(r)).isEqualTo(200);
            assertThat((String) read(r, "$.status")).isEqualTo("CANCELED");
            assertThat(db.queryForObject("SELECT canceled_after_contact FROM purchase_orders WHERE order_id=?", Integer.class,
                    o[0])).isZero();
            assertThat(db.queryForObject("SELECT canceled_by FROM purchase_orders WHERE order_id=?", Long.class, o[0]))
                    .isEqualTo(adminId("shini.admin2"));
            assertThat(eventTypes(o[0]).get(eventTypes(o[0]).size() - 1)).isEqualTo("CANCELED");
        }
        assertThat(reservedBy(labneh)).isNull();
        assertThat(reservedBy(milk)).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).as("history kept").isEqualTo(2);
        assertThat((String) read(call("POST", "/api/purchase-orders/" + pending + "/cancel", shini.adminToken(),
                "{\"version\":1,\"reason\":\"Again\"}"), "$.code")).isEqualTo("INVALID_TRANSITION");
    }

    // ── reads, history, roles, isolation ────────────────────────────────────────────

    @Test
    void listDetailsAndHistoryShowOnlyTheStoresOwnOrders() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long a = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);
        long b = createOk(shini.adminToken(), dairy, "MANUAL", milk, 2);
        call("POST", "/api/purchase-orders/" + b + "/approve", shini.adminToken(), "{\"version\":0}");
        long nDairy = supplier(neshan.adminToken(), "Dairy Co", null);
        long nLabneh = product(neshan.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long n = createOk(neshan.adminToken(), nDairy, "MANUAL", nLabneh, 1);

        MvcResult list = call("GET", "/api/purchase-orders", shini.adminToken(), null);
        assertThat(((Number) read(list, "$.total")).intValue()).isEqualTo(2);
        assertThat((List<Object>) read(list, "$.orders[*].orderId")).containsExactly((int) b, (int) a);
        MvcResult pending = call("GET", "/api/purchase-orders?status=PENDING_APPROVAL", shini.adminToken(), null);
        assertThat((List<Object>) read(pending, "$.orders[*].orderId")).containsExactly((int) a);
        MvcResult page = call("GET", "/api/purchase-orders?page=1&size=1", shini.adminToken(), null);
        assertThat((List<Object>) read(page, "$.orders[*].orderId")).containsExactly((int) a);
        assertThat(status(call("GET", "/api/purchase-orders?status=SHIPPED", shini.adminToken(), null))).isEqualTo(400);
        MvcResult details = call("GET", "/api/purchase-orders/" + b, shini.adminToken(), null);
        assertThat((String) read(details, "$.items[0].productName")).isEqualTo("Milk");
        MvcResult history = call("GET", "/api/purchase-orders/" + b + "/events", shini.adminToken(), null);
        assertThat((List<Object>) read(history, "$[*].type")).containsExactly("CREATED", "APPROVED");
        assertThat((List<Object>) read(history, "$[*].user.userId"))
                .containsOnly((int) adminId("shini.admin"));

        // Neshan cannot see or touch Shini's orders (404, nothing changes), and the reverse
        for (String[] c : new String[][]{{"GET", "/api/purchase-orders/" + a, null}, {"GET", "/api/purchase-orders/" + a + "/events", null},
                {"PUT", "/api/purchase-orders/" + a, "{\"version\":0,\"items\":[]}"},
                {"POST", "/api/purchase-orders/" + a + "/approve", "{\"version\":0}"},
                {"POST", "/api/purchase-orders/" + a + "/reject", "{\"version\":0,\"reason\":\"Hacked\"}"},
                {"POST", "/api/purchase-orders/" + b + "/cancel", "{\"version\":1,\"reason\":\"Hacked\"}"}}) {
            MvcResult r = call(c[0], c[1], neshan.adminToken(), c[2]);
            assertThat(status(r)).as(c[0] + " " + c[1]).isEqualTo(404);
            assertThat(body(r)).doesNotContain("Labneh").doesNotContain("PO-000001\"");
        }
        assertThat(state(a)).isEqualTo("PENDING_APPROVAL");
        assertThat(state(b)).isEqualTo("APPROVED");
        assertThat(events(a)).isEqualTo(1);
        assertThat(status(call("GET", "/api/purchase-orders/" + n, shini.adminToken(), null))).isEqualTo(404);
        MvcResult nList = call("GET", "/api/purchase-orders", neshan.adminToken(), null);
        assertThat((List<Object>) read(nList, "$.orders[*].orderId")).containsExactly((int) n);
    }

    @Test
    void onlyStoreAdminsCanUsePurchasing() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long id = createOk(shini.adminToken(), dairy, "MANUAL", labneh, 5);
        String sys = sysadminToken();
        String create = orderJson(UUID.randomUUID().toString(), dairy, "MANUAL", null, items(labneh, 1), null);
        String[][] calls = {{"GET", "/api/purchase-recommendations", null}, {"GET", "/api/purchase-candidates?supplierId=" + dairy, null},
                {"POST", "/api/purchase-orders", create}, {"GET", "/api/purchase-orders", null},
                {"GET", "/api/purchase-orders/" + id, null}, {"GET", "/api/purchase-orders/" + id + "/events", null},
                {"PUT", "/api/purchase-orders/" + id, "{\"version\":0,\"items\":[]}"},
                {"POST", "/api/purchase-orders/" + id + "/approve", "{\"version\":0}"},
                {"POST", "/api/purchase-orders/" + id + "/reject", "{\"version\":0,\"reason\":\"No way\"}"},
                {"POST", "/api/purchase-orders/" + id + "/cancel", "{\"version\":0,\"reason\":\"No way\"}"}};
        for (String[] c : calls) {
            assertThat(status(call(c[0], c[1], shini.cashierToken(), c[2]))).as("cashier " + c[1]).isEqualTo(403);
            assertThat(status(call(c[0], c[1], sys, c[2]))).as("sysadmin " + c[1]).isEqualTo(403);
            assertThat(status(call(c[0], c[1], null, c[2]))).as("no login " + c[1]).isEqualTo(401);
        }
        assertThat(state(id)).isEqualTo("PENDING_APPROVAL");
        assertThat(version(id)).isZero();
        assertThat(events(id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders", Long.class)).isEqualTo(1);
    }

    @Test
    void theWholeWorkflowNeverChangesStockPricesCostsSalesOrReports() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long labneh = product(shini.adminToken(), "Labneh", "11111111", 4, 5, "Dairy Co", 9.5, 30);
        long milk = product(shini.adminToken(), "Milk", "22222222", 2, 5, "Dairy Co", 3.0, 30);
        for (int d = 0; d <= 13; d++) sold(shini.storeId(), labneh, 2, d);
        long line = sold(shini.storeId(), milk, 5, 2);
        returned(shini.storeId(), milk, line, 1, 1);
        guardian(shini.adminToken(), true);
        Map<String, Object> before = financialSnapshot();
        String summary = body(call("GET", "/api/reports/summary?from=2026-01-01&to=2030-12-31", shini.adminToken(), null));
        String dashboard = body(call("GET", "/api/guardian/dashboard", shini.adminToken(), null))
                .replaceAll("\"generatedAt\":\"[^\"]*\"", "");

        long g = createOk(shini.adminToken(), dairy, "GUARDIAN", labneh, 29);
        long m = createOk(shini.adminToken(), dairy, "MANUAL", milk, 8);
        call("PUT", "/api/purchase-orders/" + m, shini.adminToken(), "{\"version\":0,\"items\":[{\"productId\":" + milk + ",\"quantity\":10}]}");
        call("POST", "/api/purchase-orders/" + g + "/approve", shini.adminToken(), "{\"version\":0}");
        call("POST", "/api/purchase-orders/" + m + "/reject", shini.adminToken(), "{\"version\":1,\"reason\":\"Too much\"}");
        call("POST", "/api/purchase-orders/" + g + "/cancel", shini.adminToken(), "{\"version\":1,\"reason\":\"Changed plan\"}");

        assertThat(financialSnapshot()).isEqualTo(before);
        assertThat(body(call("GET", "/api/reports/summary?from=2026-01-01&to=2030-12-31", shini.adminToken(), null)))
                .isEqualTo(summary);
        assertThat(body(call("GET", "/api/guardian/dashboard", shini.adminToken(), null))
                .replaceAll("\"generatedAt\":\"[^\"]*\"", "")).as("Guardian indicators unchanged").isEqualTo(dashboard);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE status IN "
                + "('MESSAGE_GENERATED','SENT','CONFIRMED','CLOSED')", Long.class)).isZero();
    }
}
