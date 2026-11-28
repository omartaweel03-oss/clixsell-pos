package com.clixsell.pos.shift;

import com.clixsell.pos.ApiTestBase;
import com.clixsell.pos.common.DatabaseRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Features 1 and 2 (D2, D3): logical cash drawers, cashier shifts, expected cash, closing with the
 * counted cash, Store Admin exceptional closing and cash in / out, optional vs mandatory shifts,
 * refunds into the current shift, store isolation, and the database's own checks behind them.
 */
class ShiftApiTest extends ApiTestBase {

    private StoreSetup shop;
    private StoreSetup other;
    private long water; // 31.90 + 16% = 37.00
    private long drawer;

    @BeforeEach
    void setUp() throws Exception {
        shop = setupStore("Shift Store", "shift");
        other = setupStore("Other Store", "othershift");
        water = createProduct(shop.adminToken(), "Water Pack", "4006381333931", 31.90, 50);
        drawer = jsonLong(as(shop.adminToken(), post("/api/drawers"), "{\"name\":\"Till 1\"}")
                .andExpect(status().isCreated()).andReturn(), "drawerId");
    }

    private ResultActions as(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r,
                             String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", bearer(token)));
    }

    private ResultActions sell(String token, String method, int qty) throws Exception {
        return as(token, post("/api/sales"), "{\"items\":[{\"productId\":" + water + ",\"quantity\":" + qty
                + "}],\"paymentMethod\":\"" + method + "\"}");
    }

    private long openShift(String token, String cash) throws Exception {
        return jsonLong(as(token, post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":" + cash + "}")
                .andExpect(status().isCreated()).andReturn(), "shiftId");
    }

    private ResultActions returnOne(String token, long saleId) throws Exception {
        long item = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ?", Long.class, saleId);
        return as(token, post("/api/returns"), "{\"saleId\":" + saleId + ",\"saleItemId\":" + item
                + ",\"quantity\":1,\"reason\":\"Damaged\"}");
    }

    private ResultActions movement(String token, long shift, String type, String amount, String key) throws Exception {
        return as(token, post("/api/shifts/" + shift + "/movements"),
                "{\"type\":\"" + type + "\",\"amount\":" + amount + ",\"reason\":\"Bank deposit\",\"requestKey\":\"" + key + "\"}");
    }

    private void requireShifts(boolean on) throws Exception {
        as(shop.adminToken(), put("/api/store/settings"), "{\"requireCashShift\":" + on + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.requireCashShift").value(on));
    }

    private long count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    // ── D2: optional by default ─────────────────────────────────────────────────────

    @Test
    void shiftsAreOptionalByDefault_salesWithoutAShiftWorkAsBefore() throws Exception {
        as(shop.adminToken(), get("/api/store"), null).andExpect(jsonPath("$.requireCashShift").value(false));
        long sale = jsonLong(sell(shop.cashierToken(), "CASH", 1).andExpect(status().isCreated()).andReturn(), "saleId");
        assertThat(db.queryForObject("SELECT shift_id FROM sales WHERE sale_id = ?", Long.class, sale)).isNull();
        returnOne(shop.cashierToken(), sale).andExpect(status().isCreated());
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.shift").doesNotExist())
                .andExpect(jsonPath("$.requireCashShift").value(false))
                .andExpect(jsonPath("$.drawers[0].name").value("Till 1"));
    }

    @Test
    void mandatoryShifts_saleAndCashRefundAreRefusedWithoutAShift_nothingChanges() throws Exception {
        long earlier = jsonLong(sell(shop.cashierToken(), "CASH", 1).andReturn(), "saleId");
        requireShifts(true);
        long sales = count("sales"), payments = count("payments"), returns = count("returns");
        int stock = db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, water);
        sell(shop.cashierToken(), "CASH", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_REQUIRED"));
        sell(shop.cashierToken(), "CARD", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_REQUIRED"));
        returnOne(shop.cashierToken(), earlier).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_REQUIRED"));
        assertThat(List.of(count("sales"), count("payments"), count("returns"))).containsExactly(sales, payments, returns);
        assertThat(db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, water)).isEqualTo(stock);

        long shift = openShift(shop.cashierToken(), "100");
        long sale = jsonLong(sell(shop.cashierToken(), "CASH", 1).andExpect(status().isCreated()).andReturn(), "saleId");
        assertThat(db.queryForObject("SELECT shift_id FROM sales WHERE sale_id = ?", Long.class, sale)).isEqualTo(shift);
        // the other store is not affected by this store's rule
        long otherWater = createProduct(other.adminToken(), "Water", "4006381333931", 10, 5);
        as(other.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + otherWater
                + ",\"quantity\":1}],\"paymentMethod\":\"CASH\"}").andExpect(status().isCreated());
    }

    // ── Scenario A: a normal cash shift ─────────────────────────────────────────────

    @Test
    void scenarioA_normalCashShift_expectedCashCountedCashAndDifference() throws Exception {
        long shift = openShift(shop.cashierToken(), "100.00");
        as(shop.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + water
                + ",\"quantity\":1}],\"paymentMethod\":\"CASH\",\"cashReceived\":50}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.changeDue").value(13.0));
        long cashSale2 = jsonLong(sell(shop.cashierToken(), "CASH", 1).andReturn(), "saleId");
        sell(shop.cashierToken(), "CARD", 1).andExpect(status().isCreated());
        returnOne(shop.cashierToken(), cashSale2).andExpect(status().isCreated()).andExpect(jsonPath("$.refundAmount").value(37.0));
        movement(shop.adminToken(), shift, "CASH_IN", "20", "req-in-00001").andExpect(status().isCreated());
        movement(shop.adminToken(), shift, "CASH_OUT", "30.50", "req-out-0001").andExpect(status().isCreated());
        // the same request again (network retry): nothing new
        movement(shop.adminToken(), shift, "CASH_OUT", "30.50", "req-out-0001").andExpect(status().isCreated());
        assertThat(count("cash_movements")).isEqualTo(2);

        // 100 + 37 + 37 − 37 + 20 − 30.50 = 126.50 (the 37.00 card sale is not cash)
        as(shop.cashierToken(), get("/api/shifts/current"), null)
                .andExpect(jsonPath("$.shift.shiftId").value(shift))
                .andExpect(jsonPath("$.shift.openingCash").value(100.0))
                .andExpect(jsonPath("$.shift.cashSales").value(74.0))
                .andExpect(jsonPath("$.shift.cashSalesCount").value(2))
                .andExpect(jsonPath("$.shift.cashRefunds").value(37.0))
                .andExpect(jsonPath("$.shift.cashIn").value(20.0))
                .andExpect(jsonPath("$.shift.cashOut").value(30.5))
                .andExpect(jsonPath("$.shift.otherSales").value(37.0))
                .andExpect(jsonPath("$.shift.expectedCash").value(126.5));

        // counted 125.50: a difference needs a note
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":125.50}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.note").exists());
        assertThat(db.queryForObject("SELECT status FROM cash_shifts WHERE shift_id = ?", String.class, shift)).isEqualTo("OPEN");
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":125.50,\"note\":\"1.00 missing\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.expectedCash").value(126.5))
                .andExpect(jsonPath("$.countedCash").value(125.5))
                .andExpect(jsonPath("$.difference").value(-1.0))
                .andExpect(jsonPath("$.closeKind").value("CASHIER"));
        // closing again is refused; the record stays as it was
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":126.50}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_CLOSED"));
        movement(shop.adminToken(), shift, "CASH_IN", "5", "req-late-001").andExpect(status().isConflict());

        // after closing, a refund of an old sale of that shift goes to the NEW shift; the closed one is unchanged
        long shift2 = openShift(shop.cashierToken(), "50");
        long first = db.queryForObject("SELECT MIN(sale_id) FROM sales WHERE shift_id = ?", Long.class, shift);
        returnOne(shop.cashierToken(), first).andExpect(status().isCreated());
        assertThat(db.queryForObject("SELECT shift_id FROM returns WHERE sale_id = ?", Long.class, first)).isEqualTo(shift2);
        as(shop.adminToken(), get("/api/shifts/" + shift), null)
                .andExpect(jsonPath("$.shift.expectedCash").value(126.5))
                .andExpect(jsonPath("$.shift.cashRefunds").value(37.0))
                .andExpect(jsonPath("$.sales.length()").value(3))
                .andExpect(jsonPath("$.movements.length()").value(2));
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(13.0));
    }

    // ── D3: who may do what ─────────────────────────────────────────────────────────

    @Test
    void permissions_cashierOwnShiftOnly_adminExceptionWithReason_movementsAdminOnly() throws Exception {
        long admin2 = createCashier(shop.adminToken(), "shift.cashier2", CASHIER_PASSWORD);
        String cashier2 = login("shift.cashier2", CASHIER_PASSWORD);
        long shift = openShift(shop.cashierToken(), "10");
        // one open shift per drawer and per user
        as(cashier2, post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRAWER_IN_USE"));
        as(shop.cashierToken(), post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_ALREADY_OPEN"));
        // drawers and cash movements: Store Admin only
        as(shop.cashierToken(), post("/api/drawers"), "{\"name\":\"Mine\"}").andExpect(status().isForbidden());
        movement(shop.cashierToken(), shift, "CASH_OUT", "5", "req-cash-001").andExpect(status().isForbidden());
        // another cashier cannot close or read this shift
        as(cashier2, post("/api/shifts/" + shift + "/close"), "{\"countedCash\":10}").andExpect(status().isForbidden());
        as(cashier2, get("/api/shifts/" + shift), null).andExpect(status().isForbidden());
        as(cashier2, get("/api/shifts"), null).andExpect(status().isForbidden());
        // a cashier cannot use the admin closing
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/admin-close"), "{\"countedCash\":10,\"reason\":\"Leaving\"}")
                .andExpect(status().isForbidden());
        // the Store Admin's exceptional closing needs a reason
        as(shop.adminToken(), post("/api/shifts/" + shift + "/admin-close"), "{\"countedCash\":10}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.reason").exists());
        as(shop.adminToken(), post("/api/shifts/" + shift + "/admin-close"),
                "{\"countedCash\":10,\"reason\":\"Cashier went home ill\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.closeKind").value("ADMIN_EXCEPTION"))
                .andExpect(jsonPath("$.closeReason").value("Cashier went home ill"));
        assertThat(db.queryForObject("SELECT closed_by FROM cash_shifts WHERE shift_id = ?", Long.class, shift))
                .isNotEqualTo(admin2).isNotNull();
        // a deactivated drawer cannot be used
        as(shop.adminToken(), patch("/api/drawers/" + drawer), "{\"active\":false}").andExpect(status().isOk());
        as(cashier2, post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":0}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRAWER_INACTIVE"));
    }

    @Test
    void storeIsolation_noAccessToAnotherStoresDrawerOrShift() throws Exception {
        long shift = openShift(shop.cashierToken(), "10");
        as(other.cashierToken(), post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":0}")
                .andExpect(status().isNotFound());
        as(other.adminToken(), get("/api/shifts/" + shift), null).andExpect(status().isNotFound());
        as(other.adminToken(), post("/api/shifts/" + shift + "/admin-close"), "{\"countedCash\":0,\"reason\":\"Not mine\"}")
                .andExpect(status().isNotFound());
        movement(other.adminToken(), shift, "CASH_IN", "5", "req-iso-0001").andExpect(status().isNotFound());
        as(other.adminToken(), get("/api/shifts"), null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        as(other.adminToken(), get("/api/drawers"), null).andExpect(jsonPath("$.length()").value(0));
        as(sysadminToken(), get("/api/shifts/current"), null).andExpect(status().isForbidden());
    }

    @Test
    void amountsAreValidated_andAnImpossibleCashOutIsRefused() throws Exception {
        for (String bad : new String[]{"-1", "1.001", "null", "\"x\""}) {
            MvcResult r = as(shop.cashierToken(), post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":" + bad + "}")
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
        }
        long shift = openShift(shop.cashierToken(), "10");
        movement(shop.adminToken(), shift, "CASH_OUT", "10.01", "req-big-0001").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_OUT_TOO_LARGE"));
        movement(shop.adminToken(), shift, "CASH_OUT", "0", "req-zero-001").andExpect(status().isBadRequest());
        movement(shop.adminToken(), shift, "REFUND", "1", "req-type-001").andExpect(status().isBadRequest());
        movement(shop.adminToken(), shift, "CASH_OUT", "10", "short").andExpect(status().isBadRequest());
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":-1}").andExpect(status().isBadRequest());
        assertThat(count("cash_movements")).isZero();
    }

    // ── races ───────────────────────────────────────────────────────────────────────

    @Test
    void race_twoClosesAtOnce_exactlyOneWins() throws Exception {
        long shift = openShift(shop.cashierToken(), "10");
        List<Integer> codes = parallel(2, i -> as(i == 0 ? shop.cashierToken() : shop.adminToken(),
                post("/api/shifts/" + shift + (i == 0 ? "/close" : "/admin-close")),
                "{\"countedCash\":10,\"reason\":\"Closing for the night\"}").andReturn().getResponse().getStatus());
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(db.queryForObject("SELECT version FROM cash_shifts WHERE shift_id = ?", Long.class, shift)).isEqualTo(1);
    }

    @Test
    void race_salesRefundsAndMovementsWhileClosing_theRecordedExpectedCashIsExactlyWhatWasInTheShift() throws Exception {
        long shift = openShift(shop.cashierToken(), "100");
        long base = jsonLong(sell(shop.cashierToken(), "CASH", 2).andReturn(), "saleId");
        List<Integer> codes = parallel(4, i -> switch (i) {
            case 0 -> sell(shop.cashierToken(), "CASH", 1).andReturn().getResponse().getStatus();
            case 1 -> returnOne(shop.cashierToken(), base).andReturn().getResponse().getStatus();
            case 2 -> movement(shop.adminToken(), shift, "CASH_IN", "7", "req-race-001").andReturn().getResponse().getStatus();
            default -> {
                long expected = Math.round(db.queryForObject("SELECT 0", Long.class)); // read nothing: the server decides
                yield as(shop.adminToken(), post("/api/shifts/" + shift + "/admin-close"),
                        "{\"countedCash\":0,\"note\":\"Counted at the end\",\"reason\":\"End of day\"}")
                        .andReturn().getResponse().getStatus() + (int) expected;
            }
        });
        assertThat(codes.get(3)).isEqualTo(200);
        // whatever got in before the close is in the recorded expected cash; nothing got in after it
        long recorded = db.queryForObject("SELECT expected_cash_minor FROM cash_shifts WHERE shift_id = ?", Long.class, shift);
        long fromRecords = 10000
                + db.queryForObject("SELECT coalesce(sum(CAST(round(p.amount*100) AS INTEGER)),0) FROM payments p JOIN sales s "
                + "ON s.sale_id = p.sale_id WHERE s.shift_id = ? AND p.payment_method = 'CASH'", Long.class, shift)
                - db.queryForObject("SELECT coalesce(sum(refund_minor),0) FROM returns WHERE shift_id = ?", Long.class, shift)
                + db.queryForObject("SELECT coalesce(sum(amount_minor),0) FROM cash_movements WHERE shift_id = ?", Long.class, shift);
        assertThat(recorded).isEqualTo(fromRecords);
        // a sale that came after the close is simply not in that shift (shifts are optional here)
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sales WHERE shift_id = ? AND sale_id > ?", Long.class, shift, base))
                .isLessThanOrEqualTo(1);
    }

    private interface Work {
        int run(int i) throws Exception;
    }

    private static List<Integer> parallel(int n, Work work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit((Callable<Integer>) () -> {
                    start.await();
                    return work.run(k);
                }));
            }
            start.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : fs) out.add(f.get());
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ── refunds never add up to more than was paid ──────────────────────────────────

    @Test
    void partialReturns_lastReturnPaysTheRemainder_neverMoreThanPaid() throws Exception {
        as(shop.adminToken(), put("/api/store/settings"), "{\"taxRate\":0}").andExpect(status().isOk());
        long p = createProduct(shop.adminToken(), "Juice", "5012345678900", 3.34, 10);
        // 3 × 3.34 = 10.02 − 0.01 = 10.01: one unit is 3.3367
        long sale = jsonLong(as(shop.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + p
                + ",\"quantity\":3}],\"paymentMethod\":\"CASH\",\"discountType\":\"AMOUNT\",\"discountValue\":0.01}")
                .andExpect(jsonPath("$.total").value(10.01)).andReturn(), "saleId");
        List<Double> refunds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MvcResult r = returnOne(shop.cashierToken(), sale).andExpect(status().isCreated()).andReturn();
            refunds.add(Double.parseDouble(r.getResponse().getContentAsString().replaceAll("(?s).*\"refundAmount\":([0-9.]+).*", "$1")));
        }
        assertThat(refunds).containsExactly(3.34, 3.34, 3.33);
        assertThat(db.queryForObject("SELECT sum(refund_minor) FROM returns WHERE sale_id = ?", Long.class, sale)).isEqualTo(1001);
    }

    /**
     * CURRENT POLICY (documented for the pre-merge review, NOT a decision): a cash refund is accepted
     * even when the drawer's expected cash cannot cover it; a cashier can do it alone (no Store Admin
     * approval); the shift's expected cash goes negative and the closing then needs a note. A cash
     * OUT of the same size is refused. Any change of this policy needs management approval.
     */
    @Test
    void currentPolicy_cashRefundBeyondTheDrawer_isAccepted_byTheCashierAlone_expectedGoesNegative() throws Exception {
        long earlier = jsonLong(sell(shop.cashierToken(), "CASH", 1).andReturn(), "saleId"); // 37.00, no shift
        long shift = openShift(shop.cashierToken(), "0");
        returnOne(shop.cashierToken(), earlier).andExpect(status().isCreated()).andExpect(jsonPath("$.refundAmount").value(37.0));
        assertThat(db.queryForObject("SELECT shift_id FROM returns WHERE sale_id = ?", Long.class, earlier)).isEqualTo(shift);
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(-37.0));
        // the same amount as a cash OUT is refused (it is not in the drawer)
        movement(shop.adminToken(), shift, "CASH_OUT", "37", "req-cmp-0001").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASH_OUT_TOO_LARGE"));
        // closing with the 0.00 actually counted shows the 37.00 difference and needs a note
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":0}").andExpect(status().isBadRequest());
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":0,\"note\":\"Refund paid from my pocket\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.difference").value(37.0))
                .andExpect(jsonPath("$.expectedCash").value(-37.0));
    }

    @Test
    void databaseRuleCodesBecomeClearMessages() {
        RuntimeException e = new RuntimeException("could not execute statement",
                new java.sql.SQLException("[SQLITE_CONSTRAINT_TRIGGER] A RAISE function within a trigger fired, causing "
                        + "the SQL statement to abort (SHIFT_CLOSED_RECORD)"));
        assertThat(DatabaseRules.code(e)).isEqualTo("SHIFT_CLOSED_RECORD");
        assertThat(DatabaseRules.message("SHIFT_CLOSED_RECORD")).contains("closed shift");
        assertThat(DatabaseRules.code(new RuntimeException("disk I/O error"))).isNull();
    }
}
