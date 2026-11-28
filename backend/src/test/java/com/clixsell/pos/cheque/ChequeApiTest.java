package com.clixsell.pos.cheque;

import com.clixsell.pos.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 9 (scenario E): CHEQUE payment at checkout, lifecycle with an audit trail, returns by the
 * Store Admin into PENDING refunds (never automatic cash), payout only after CLEARED — cash from the
 * admin's shift (atomic movement) or bank transfer — and the store's isolation.
 */
class ChequeApiTest extends ApiTestBase {

    private StoreSetup shop;
    private StoreSetup other;
    private long tv; // 1,000.00

    @BeforeEach
    void setUp() throws Exception {
        shop = setupStore("Cheque Store", "chq");
        other = setupStore("Cheque Other", "chqother");
        as(shop.adminToken(), put("/api/store/settings"), "{\"taxRate\":0}").andExpect(status().isOk());
        tv = createProduct(shop.adminToken(), "Television", "4006381333931", 500, 10);
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder r, String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", bearer(token)));
    }

    private static String cheque(String number, String extra) {
        return ",\"cheque\":{\"number\":\"" + number + "\",\"bank\":\"Bank of Palestine\",\"dueDate\":\""
                + LocalDate.now().plusDays(3) + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private ResultActions sellByCheque(String token, int qty, String chequePart) throws Exception {
        return as(token, post("/api/sales"), "{\"items\":[{\"productId\":" + tv + ",\"quantity\":" + qty
                + "}],\"paymentMethod\":\"CHEQUE\"" + chequePart + "}");
    }

    private ResultActions move(long id, String to, String note) throws Exception {
        return as(shop.adminToken(), post("/api/cheques/" + id + "/status"), "{\"toStatus\":\"" + to + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}");
    }

    private ResultActions returnOne(String token, long sale) throws Exception {
        long item = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ?", Long.class, sale);
        return as(token, post("/api/returns"), "{\"saleId\":" + sale + ",\"saleItemId\":" + item + ",\"quantity\":1,\"reason\":\"Faulty\"}");
    }

    private long count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void checkout_chequeRecordedReceived_forTheFullAmount_inTheSameTransaction() throws Exception {
        long sale = jsonLong(sellByCheque(shop.cashierToken(), 2, cheque("100245", "\"amount\":1000,\"notes\":\"Post-dated\""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.paymentMethod").value("CHEQUE")).andReturn(), "saleId");
        assertThat(db.queryForMap("SELECT cheque_number, bank_name, amount_minor, status, notes FROM cheques WHERE sale_id = ?", sale))
                .containsEntry("cheque_number", "100245").containsEntry("amount_minor", 100000)
                .containsEntry("status", "RECEIVED").containsEntry("notes", "Post-dated");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM cheque_events", Long.class)).isEqualTo(1); // written by the database
        // refused before anything changes: no details, wrong amount, the same cheque twice, details on a cash sale
        long sales = count("sales"), stock = db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Long.class, tv);
        sellByCheque(shop.cashierToken(), 1, "").andExpect(status().isBadRequest());
        sellByCheque(shop.cashierToken(), 1, cheque("100246", "\"amount\":499")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['cheque.amount']").exists());
        sellByCheque(shop.cashierToken(), 1, cheque("100245", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHEQUE_DUPLICATE"));
        as(shop.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + tv + ",\"quantity\":1}],\"paymentMethod\":\"CASH\""
                + cheque("9", null) + "}").andExpect(status().isBadRequest());
        assertThat(count("sales")).isEqualTo(sales);
        assertThat(db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Long.class, tv)).isEqualTo(stock);
        // the other store may record a cheque with the same number at the same bank
        long theirs = createProduct(other.adminToken(), "Radio", "5012345678900", 50, 5);
        as(other.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + theirs + ",\"quantity\":1}],\"paymentMethod\":\"CHEQUE\""
                + cheque("100245", null) + "}").andExpect(status().isCreated());
    }

    @Test
    void scenarioE_lifecycle_auditTrail_returnPendingUntilCleared_cashPayoutAtomic() throws Exception {
        long sale = jsonLong(sellByCheque(shop.cashierToken(), 2, cheque("700001", null)).andReturn(), "saleId");
        long cheque = db.queryForObject("SELECT cheque_id FROM cheques WHERE sale_id = ?", Long.class, sale);
        // cashiers neither manage cheques nor return a cheque sale
        as(shop.cashierToken(), get("/api/cheques"), null).andExpect(status().isForbidden());
        returnOne(shop.cashierToken(), sale).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHEQUE_RETURN_NEEDS_STORE_ADMIN"));
        assertThat(count("returns")).isZero();

        move(cheque, "CLEARED", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHEQUE_TRANSITION_NOT_ALLOWED"));
        move(cheque, "DEPOSITED", null).andExpect(status().isOk()).andExpect(jsonPath("$.cheque.status").value("DEPOSITED"));
        move(cheque, "BOUNCED", null).andExpect(status().isBadRequest());
        move(cheque, "BOUNCED", "Insufficient funds").andExpect(status().isOk());
        move(cheque, "DEPOSITED", "Deposited again").andExpect(status().isOk());

        // a return while the cheque is not cleared: owed, PENDING; no cash moves
        returnOne(shop.adminToken(), sale).andExpect(status().isCreated()).andExpect(jsonPath("$.refundMethod").value("CHEQUE"));
        long refund = db.queryForObject("SELECT cheque_refund_id FROM cheque_refunds", Long.class);
        assertThat(db.queryForObject("SELECT status FROM cheque_refunds", String.class)).isEqualTo("PENDING");
        as(shop.adminToken(), post("/api/cheque-refunds/" + refund + "/pay"), "{\"method\":\"BANK_TRANSFER\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHEQUE_NOT_CLEARED"));

        move(cheque, "CLEARED", null).andExpect(status().isOk());
        move(cheque, "BOUNCED", "Too late").andExpect(status().isConflict()); // final
        // cash payout needs the admin's own open shift, and comes out of it
        as(shop.adminToken(), post("/api/cheque-refunds/" + refund + "/pay"), "{\"method\":\"CASH\",\"requestKey\":\"pay-0000001\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SHIFT_REQUIRED"));
        long drawer = jsonLong(as(shop.adminToken(), post("/api/drawers"), "{\"name\":\"Office\"}").andReturn(), "drawerId");
        long shift = jsonLong(as(shop.adminToken(), post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":800}")
                .andExpect(status().isCreated()).andReturn(), "shiftId");
        as(shop.adminToken(), post("/api/cheque-refunds/" + refund + "/pay"), "{\"method\":\"CASH\",\"requestKey\":\"pay-0000001\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.refunds[0].status").value("PAID"))
                .andExpect(jsonPath("$.refunds[0].paidMethod").value("CASH"));
        assertThat(db.queryForMap("SELECT type, amount_minor, shift_id FROM cash_movements WHERE cheque_refund_id = ?", refund))
                .containsEntry("type", "CHEQUE_REFUND_OUT").containsEntry("amount_minor", 50000).containsEntry("shift_id", (int) shift);
        as(shop.adminToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(300.0));
        as(shop.adminToken(), post("/api/cheque-refunds/" + refund + "/pay"), "{\"method\":\"CASH\",\"requestKey\":\"pay-0000002\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHEQUE_REFUND_DECIDED"));

        // the second unit: paid by bank transfer, no drawer movement
        returnOne(shop.adminToken(), sale).andExpect(status().isCreated());
        long refund2 = db.queryForObject("SELECT max(cheque_refund_id) FROM cheque_refunds", Long.class);
        as(shop.adminToken(), post("/api/cheque-refunds/" + refund2 + "/pay"), "{\"method\":\"BANK_TRANSFER\"}").andExpect(status().isOk());
        assertThat(count("cash_movements")).isEqualTo(1);
        as(shop.adminToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":300}").andExpect(status().isOk());

        // the audit trail, in order, with who did it
        as(shop.adminToken(), get("/api/cheques/" + cheque), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(5))
                .andExpect(jsonPath("$.events[0].toStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.events[2].toStatus").value("BOUNCED"))
                .andExpect(jsonPath("$.events[2].note").value("Insufficient funds"))
                .andExpect(jsonPath("$.events[4].toStatus").value("CLEARED"))
                .andExpect(jsonPath("$.refunds.length()").value(2));
        as(shop.adminToken(), get("/api/cheques/summary"), null).andExpect(jsonPath("$.countByStatus.CLEARED").value(1))
                .andExpect(jsonPath("$.pendingRefundCount").value(0));
    }

    @Test
    void bouncedChequeGivenBack_refundCancelledWithReason_andTheDatabaseKeepsTheRules() throws Exception {
        long sale = jsonLong(sellByCheque(shop.cashierToken(), 1, cheque("800001", null)).andReturn(), "saleId");
        long cheque = db.queryForObject("SELECT cheque_id FROM cheques WHERE sale_id = ?", Long.class, sale);
        move(cheque, "DEPOSITED", null).andExpect(status().isOk());
        move(cheque, "BOUNCED", "Account closed").andExpect(status().isOk());
        returnOne(shop.adminToken(), sale).andExpect(status().isCreated());
        long refund = db.queryForObject("SELECT cheque_refund_id FROM cheque_refunds", Long.class);
        as(shop.adminToken(), post("/api/cheque-refunds/" + refund + "/cancel"), "{\"reason\":\"\"}").andExpect(status().isBadRequest());
        as(shop.adminToken(), post("/api/cheque-refunds/" + refund + "/cancel"), "{\"reason\":\"Cheque bounced; goods returned\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.refunds[0].status").value("CANCELLED"));
        move(cheque, "CANCELLED", "Bounced cheque given back").andExpect(status().isOk());
        // direct SQL: no status change without its event, no deletion, no change of the amount
        assertThatThrownBy(() -> db.update("UPDATE cheques SET status = 'CLEARED', version = version + 1 WHERE cheque_id = ?", cheque))
                .hasMessageContaining("CHEQUE");
        assertThatThrownBy(() -> db.update("DELETE FROM cheques WHERE cheque_id = ?", cheque)).hasMessageContaining("CHEQUE_NO_DELETE");
        assertThatThrownBy(() -> db.update("DELETE FROM cheque_events")).hasMessageContaining("CHEQUE_EVENTS_APPEND_ONLY");
    }

    @Test
    void anotherStoreSeesNothing() throws Exception {
        long sale = jsonLong(sellByCheque(shop.cashierToken(), 1, cheque("900001", null)).andReturn(), "saleId");
        long cheque = db.queryForObject("SELECT cheque_id FROM cheques WHERE sale_id = ?", Long.class, sale);
        as(other.adminToken(), get("/api/cheques"), null).andExpect(jsonPath("$.length()").value(0));
        as(other.adminToken(), get("/api/cheques/" + cheque), null).andExpect(status().isNotFound());
        as(other.adminToken(), post("/api/cheques/" + cheque + "/status"), "{\"toStatus\":\"DEPOSITED\"}").andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT status FROM cheques WHERE cheque_id = ?", String.class, cheque)).isEqualTo("RECEIVED");
    }
}
