package com.clixsell.pos.shift;

import com.clixsell.pos.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Approved cash refund policy (management decision): a CASH refund is paid only from the refunding
 * user's open drawer and only when it holds enough RECORDED cash — checked by the server in the same
 * transaction, for every role. The Store Admin resolves a shortage with a real, recorded cash-in (or the
 * refund waits); nobody can skip the check. Duplicate requests and concurrent refunds never pay twice.
 */
class CashRefundPolicyApiTest extends ApiTestBase {

    private StoreSetup shop;
    private StoreSetup other;
    private long item; // 50.00 (tax 0)
    private long drawer;

    @BeforeEach
    void setUp() throws Exception {
        shop = setupStore("Refund Store", "refund");
        other = setupStore("Refund Other", "refundother");
        as(shop.adminToken(), put("/api/store/settings"), "{\"taxRate\":0}").andExpect(status().isOk());
        item = createProduct(shop.adminToken(), "Kettle", "4006381333931", 50, 20);
        drawer = jsonLong(as(shop.adminToken(), post("/api/drawers"), "{\"name\":\"Till 1\"}").andReturn(), "drawerId");
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder r, String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", bearer(token)));
    }

    /** A cash sale of one kettle (50.00) made BEFORE the shift (so it does not add to the drawer). */
    private long earlierCashSale() throws Exception {
        return jsonLong(as(shop.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + item
                + ",\"quantity\":1}],\"paymentMethod\":\"CASH\"}").andExpect(status().isCreated()).andReturn(), "saleId");
    }

    private long openShift(String token, String cash) throws Exception {
        return jsonLong(as(token, post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":" + cash + "}")
                .andExpect(status().isCreated()).andReturn(), "shiftId");
    }

    private ResultActions refund(String token, long sale, String key) throws Exception {
        long line = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ?", Long.class, sale);
        return as(token, post("/api/returns"), "{\"saleId\":" + sale + ",\"saleItemId\":" + line
                + ",\"quantity\":1,\"reason\":\"Faulty\"" + (key == null ? "" : ",\"requestKey\":\"" + key + "\"") + "}");
    }

    private ResultActions cashIn(String token, long shift, String amount, String key) throws Exception {
        return as(token, post("/api/shifts/" + shift + "/movements"),
                "{\"type\":\"CASH_IN\",\"amount\":" + amount + ",\"reason\":\"Change added from the safe\",\"requestKey\":\"" + key + "\"}");
    }

    private long count(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    private int stock() {
        return db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, item);
    }

    @Test
    void insufficient_20available_50requested_blocked_nothingChanges_clearMessage() throws Exception {
        long sale = earlierCashSale();
        openShift(shop.cashierToken(), "20");
        int stockBefore = stock();
        refund(shop.cashierToken(), sale, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAWER_INSUFFICIENT"))
                .andExpect(jsonPath("$.fieldErrors.available").value("20.00"))
                .andExpect(jsonPath("$.fieldErrors.required").value("50.00"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("enough cash")));
        // nothing was saved: no return, stock and the line's returned count unchanged
        assertThat(count("SELECT COUNT(*) FROM returns")).isZero();
        assertThat(stock()).isEqualTo(stockBefore);
        assertThat(count("SELECT returned_quantity FROM sale_items WHERE sale_id = ?", sale)).isZero();
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(20.0));
    }

    @Test
    void sufficient_and_exactBalance_areAccepted_theDrawerEndsAtZero() throws Exception {
        long a = earlierCashSale(), b = earlierCashSale();
        long shift = openShift(shop.cashierToken(), "100");
        refund(shop.cashierToken(), a, null).andExpect(status().isCreated()).andExpect(jsonPath("$.refundAmount").value(50.0));
        // exactly 50.00 left: allowed, the drawer is empty after
        refund(shop.cashierToken(), b, null).andExpect(status().isCreated());
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(0.0));
        assertThat(count("SELECT COUNT(*) FROM returns WHERE shift_id = ?", shift)).isEqualTo(2);
    }

    @Test
    void zeroBalance_blocked_forTheStoreAdminToo_noBypass() throws Exception {
        long sale = earlierCashSale();
        openShift(shop.cashierToken(), "0");
        refund(shop.cashierToken(), sale, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRAWER_INSUFFICIENT"));
        // the Store Admin refunding from an empty own drawer is refused the same way
        long adminDrawer = jsonLong(as(shop.adminToken(), post("/api/drawers"), "{\"name\":\"Office\"}").andReturn(), "drawerId");
        as(shop.adminToken(), post("/api/shifts"), "{\"drawerId\":" + adminDrawer + ",\"openingCash\":0}").andExpect(status().isCreated());
        refund(shop.adminToken(), sale, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRAWER_INSUFFICIENT"));
        // and the Store Admin without any open shift: refused, never "unknown = enough"
        assertThat(count("SELECT COUNT(*) FROM returns")).isZero();
    }

    @Test
    void storeAdminResolves_withARealRecordedCashIn_thenTheRefundGoesThrough_closingBalanceAndAuditCorrect() throws Exception {
        long sale = earlierCashSale();
        long shift = openShift(shop.cashierToken(), "20");
        refund(shop.cashierToken(), sale, "rf-0000001").andExpect(status().isConflict());
        // the cashier cannot add cash; the Store Admin records the cash physically put in the drawer
        cashIn(shop.cashierToken(), shift, "30", "ci-0000001").andExpect(status().isForbidden());
        cashIn(shop.adminToken(), shift, "30", "ci-0000001").andExpect(status().isCreated());
        cashIn(shop.adminToken(), shift, "30", "ci-0000001").andExpect(status().isCreated()); // retry: once
        assertThat(count("SELECT COUNT(*) FROM cash_movements WHERE shift_id = ?", shift)).isEqualTo(1);
        // the same refund request (same key) now succeeds: 20 + 30 = 50 available
        refund(shop.cashierToken(), sale, "rf-0000001").andExpect(status().isCreated());
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(0.0));
        // closing: expected 0.00 = 20 + 30 − 50; counted 0 → no difference
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(0.0)).andExpect(jsonPath("$.difference").value(0.0));
        // the audit trail: who added how much and why, and the refund in the same shift
        as(shop.adminToken(), get("/api/shifts/" + shift), null)
                .andExpect(jsonPath("$.movements[0].type").value("CASH_IN"))
                .andExpect(jsonPath("$.movements[0].amountMinor").value(3000))
                .andExpect(jsonPath("$.movements[0].reason").value("Change added from the safe"))
                .andExpect(jsonPath("$.shift.cashIn").value(30.0))
                .andExpect(jsonPath("$.shift.cashRefunds").value(50.0));
    }

    @Test
    void duplicateRequests_sameKey_refundOnce_otherUseOfTheKeyRefused() throws Exception {
        long a = earlierCashSale(), b = earlierCashSale();
        openShift(shop.cashierToken(), "200");
        refund(shop.cashierToken(), a, "rf-dup00001").andExpect(status().isCreated());
        long first = count("SELECT return_id FROM returns");
        refund(shop.cashierToken(), a, "rf-dup00001").andExpect(status().isCreated())
                .andExpect(jsonPath("$.returnId").value(first));
        assertThat(count("SELECT COUNT(*) FROM returns")).isEqualTo(1);
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(150.0));
        refund(shop.cashierToken(), b, "rf-dup00001").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_KEY_USED"));
        refund(shop.cashierToken(), b, "bad key!").andExpect(status().isBadRequest());
    }

    @Test
    void concurrentRefunds_cannotSpendTheSameCashTwice() throws Exception {
        long[] sales = {earlierCashSale(), earlierCashSale(), earlierCashSale(), earlierCashSale()};
        long shift = openShift(shop.cashierToken(), "100"); // enough for exactly two 50.00 refunds
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        try {
            for (long s : sales) {
                fs.add(pool.submit((Callable<Integer>) () -> {
                    start.await();
                    return refund(shop.cashierToken(), s, null).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) codes.add(f.get());
            assertThat(codes).containsExactlyInAnyOrder(201, 201, 409, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COALESCE(SUM(refund_minor), 0) FROM returns WHERE shift_id = ?", shift)).isEqualTo(10000);
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(0.0));
    }

    @Test
    void closedShift_noRefundIntoIt_andAfterClosingANewOpenDrawerIsNeeded() throws Exception {
        long sale = earlierCashSale();
        long shift = openShift(shop.cashierToken(), "100");
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":100}").andExpect(status().isOk());
        refund(shop.cashierToken(), sale, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_REFUND_NEEDS_SHIFT"));
        long shift2 = openShift(shop.cashierToken(), "60");
        refund(shop.cashierToken(), sale, null).andExpect(status().isCreated());
        assertThat(count("SELECT shift_id FROM returns")).isEqualTo(shift2);
        assertThat(count("SELECT expected_cash_minor FROM cash_shifts WHERE shift_id = ?", shift)).isEqualTo(10000); // unchanged
    }

    @Test
    void optionalShiftStore_cashRefundWithoutADrawerIsRefused_nonCashRefundsUnchanged() throws Exception {
        long cash = earlierCashSale();
        long card = jsonLong(as(shop.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + item
                + ",\"quantity\":1}],\"paymentMethod\":\"CARD\"}").andReturn(), "saleId");
        as(shop.adminToken(), get("/api/store"), null).andExpect(jsonPath("$.requireCashShift").value(false));
        refund(shop.cashierToken(), cash, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASH_REFUND_NEEDS_SHIFT"));
        // a card refund goes back to the card: no drawer involved, unchanged behaviour
        refund(shop.cashierToken(), card, null).andExpect(status().isCreated()).andExpect(jsonPath("$.refundMethod").value("CARD"));
    }

    @Test
    void multiStore_anotherStoreCannotAddCashOrRefund_andItsDrawersAreItsOwn() throws Exception {
        long sale = earlierCashSale();
        long shift = openShift(shop.cashierToken(), "20");
        cashIn(other.adminToken(), shift, "30", "ci-other001").andExpect(status().isNotFound());
        refund(other.cashierToken(), sale, null).andExpect(status().isNotFound());
        assertThat(count("SELECT COUNT(*) FROM cash_movements")).isZero();
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(20.0));
    }

    @Test
    void historicalReturnsArePreserved_thePolicyOnlyChecksNewRefunds() throws Exception {
        // a return recorded before the policy (no shift) stays exactly as it was
        long sale = earlierCashSale();
        long line = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ?", Long.class, sale);
        db.update("INSERT INTO returns (store_id, sale_id, sale_item_id, product_id, cashier_id, quantity, reason, refund_amount, "
                + "refund_method, return_date) SELECT store_id, sale_id, ?, ?, cashier_id, 1, 'Before the policy', 50, 'CASH', "
                + "'2026-11-01T10:00:00' FROM sales WHERE sale_id = ?", line, item, sale);
        as(shop.adminToken(), get("/api/reports/summary?from=2026-11-01&to=" + java.time.LocalDate.now()), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.returnCount").value(1));
        assertThat(count("SELECT COUNT(*) FROM returns WHERE shift_id IS NULL AND refund_method = 'CASH'")).isEqualTo(1);
    }
}
