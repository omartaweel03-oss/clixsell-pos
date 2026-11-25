package com.clixsell.pos.purchase;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 (P4-D2): server-side search of the purchase-order list by order number or saved
 * supplier name. It covers ALL the store's orders (not one page), combines with the status
 * filter and paging, treats % and _ literally, validates the text, and never crosses stores.
 */
class PurchaseSearchApiTest extends PurchaseApiTestBase {

    private MvcResult list(String token, String query) throws Exception {
        return call("GET", "/api/purchase-orders" + query, token, null);
    }

    /** The search text as a real request parameter (sent encoded exactly once). */
    private MvcResult search(String token, String text, String... more) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/purchase-orders")
                .param("q", text);
        for (int i = 0; i < more.length; i += 2) req = req.param(more[i], more[i + 1]);
        if (token != null) req = req.header("Authorization", bearer(token));
        return mvc.perform(req).andReturn();
    }

    @Test
    void searchCoversEveryPageByNumberOrSupplierAndStaysInTheStore() throws Exception {
        long dairy = supplier(shini.adminToken(), "Dairy Co", "970599000001");
        long bakery = supplier(shini.adminToken(), "Bakery 50%_Off", null);
        long a = product(shini.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long b = product(shini.adminToken(), "Milk", "22222222", 3, 5, "Dairy Co", 3.0, 30);
        long c = product(shini.adminToken(), "Yogurt", "33333333", 3, 5, "Dairy Co", 2.0, 30);
        long d = product(shini.adminToken(), "Bread", "44444444", 3, 5, "Bakery 50%_Off", 1.0, 30);
        long o1 = createOk(shini.adminToken(), dairy, "MANUAL", a, 1);
        long o2 = createOk(shini.adminToken(), dairy, "MANUAL", b, 1);
        long o3 = createOk(shini.adminToken(), dairy, "MANUAL", c, 1);
        long o4 = createOk(shini.adminToken(), bakery, "MANUAL", d, 1);
        call("POST", "/api/purchase-orders/" + o2 + "/approve", shini.adminToken(), "{\"version\":0}");
        long nd = supplier(neshan.adminToken(), "Dairy Co", "970599000009");
        long np = product(neshan.adminToken(), "Labneh", "11111111", 3, 5, "Dairy Co", 9.5, 30);
        long n1 = createOk(neshan.adminToken(), nd, "MANUAL", np, 1);

        // supplier name, case-insensitive, all pages (size 2 → total 3)
        MvcResult r = list(shini.adminToken(), "?q=DAIRY&size=2");
        assertThat(status(r)).isEqualTo(200);
        assertThat(((Number) read(r, "$.total")).longValue()).isEqualTo(3);
        assertThat((List<Integer>) read(r, "$.orders[*].orderId")).containsExactly((int) o3, (int) o2);
        MvcResult page2 = list(shini.adminToken(), "?q=dairy&size=2&page=1");
        assertThat((List<Integer>) read(page2, "$.orders[*].orderId")).containsExactly((int) o1);

        // order number
        String n4 = read(call("GET", "/api/purchase-orders/" + o4, shini.adminToken(), null), "$.orderNumber");
        assertThat((List<Integer>) read(search(shini.adminToken(), n4.toLowerCase()), "$.orders[*].orderId"))
                .containsExactly((int) o4);
        // with the status filter
        assertThat((List<Integer>) read(list(shini.adminToken(), "?q=dairy&status=APPROVED"), "$.orders[*].orderId"))
                .containsExactly((int) o2);
        // % and _ are literal, not wildcards
        assertThat((List<Integer>) read(search(shini.adminToken(), "50%_"), "$.orders[*].orderId"))
                .containsExactly((int) o4);
        assertThat(((Number) read(search(shini.adminToken(), "%"), "$.total")).longValue()).isEqualTo(1);
        assertThat(((Number) read(search(shini.adminToken(), "D_iry"), "$.total")).longValue()).isZero();
        // blank = no search
        assertThat(((Number) read(search(shini.adminToken(), "   "), "$.total")).longValue()).isEqualTo(4);
        // no match
        assertThat(((Number) read(list(shini.adminToken(), "?q=nothing"), "$.total")).longValue()).isZero();

        // isolation: Neshan's search only finds Neshan's order with the same supplier name
        assertThat((List<Integer>) read(list(neshan.adminToken(), "?q=dairy"), "$.orders[*].orderId"))
                .containsExactly((int) n1);
        assertThat(((Number) read(search(neshan.adminToken(), n4), "$.total")).longValue())
                .as("same order number in another store is not found").isEqualTo(
                        db.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE store_id = ? AND order_number = ?",
                                Long.class, neshan.storeId(), n4));
    }

    @Test
    void invalidSearchTextIsRefusedAndOnlyStoreAdminsMaySearch() throws Exception {
        assertThat(status(search(shini.adminToken(), "a".repeat(80)))).isEqualTo(200);
        MvcResult tooLong = search(shini.adminToken(), "a".repeat(81));
        assertThat(status(tooLong)).isEqualTo(400);
        assertThat(body(tooLong)).contains("\"q\"");
        assertThat(status(search(shini.adminToken(), "ab\u0000c"))).isEqualTo(400);
        assertThat(status(search(shini.adminToken(), "😀".repeat(80)))).as("80 characters, not 160 UTF-16 units")
                .isEqualTo(200);
        assertThat(status(list(shini.adminToken(), "?q=x&status=SHIPPED"))).isEqualTo(400);
        assertThat(status(list(shini.cashierToken(), "?q=dairy"))).isEqualTo(403);
        assertThat(status(list(sysadminToken(), "?q=dairy"))).isEqualTo(403);
        assertThat(status(list(null, "?q=dairy"))).isEqualTo(401);
    }
}
