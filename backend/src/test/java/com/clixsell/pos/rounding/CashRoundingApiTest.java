package com.clixsell.pos.rounding;

import com.clixsell.pos.ApiTestBase;
import com.clixsell.pos.sale.Sale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 7 (D4, scenario D): cash-only invoice rounding, default none; the invoice total is never
 * changed; payment = total + rounding; change from the rounded amount; refunds rounded once, the last
 * one pays exactly what is left; the drawer's expected cash and the reports stay exact.
 */
class CashRoundingApiTest extends ApiTestBase {

    private StoreSetup shop;
    private long item; // 19.97

    @BeforeEach
    void setUp() throws Exception {
        shop = setupStore("Round Store", "round");
        as(shop.adminToken(), put("/api/store/settings"), "{\"taxRate\":0}").andExpect(status().isOk());
        item = createProduct(shop.adminToken(), "Olive Oil", "4006381333931", 19.97, 50);
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder r, String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", bearer(token)));
    }

    private void step(int minor) throws Exception {
        as(shop.adminToken(), put("/api/store/settings"), "{\"cashRoundingMinor\":" + minor + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cashRoundingMinor").value(minor));
    }

    private ResultActions sell(long product, int qty, String method, String extra) throws Exception {
        return as(shop.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + product + ",\"quantity\":" + qty
                + "}],\"paymentMethod\":\"" + method + "\"" + (extra == null ? "" : "," + extra) + "}");
    }

    @Test
    void defaultIsNoRounding() throws Exception {
        as(shop.adminToken(), get("/api/store"), null).andExpect(jsonPath("$.cashRoundingMinor").value(0));
        sell(item, 1, "CASH", "\"cashReceived\":20").andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(19.97)).andExpect(jsonPath("$.amountDue").value(19.97))
                .andExpect(jsonPath("$.roundingAdjustment").value(0.0)).andExpect(jsonPath("$.changeDue").value(0.03));
    }

    @Test
    void scenarioD_1997RoundsTo2000_50Tendered_30Change_totalUnchanged_paymentRounded() throws Exception {
        step(10);
        long sale = jsonLong(sell(item, 1, "CASH", "\"cashReceived\":50").andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(19.97))
                .andExpect(jsonPath("$.roundingAdjustment").value(0.03))
                .andExpect(jsonPath("$.amountDue").value(20.0))
                .andExpect(jsonPath("$.changeDue").value(30.0)).andReturn(), "saleId");
        assertThat(db.queryForMap("SELECT total, rounding_minor FROM sales WHERE sale_id = ?", sale))
                .containsEntry("total", 19.97).containsEntry("rounding_minor", 3);
        assertThat(db.queryForObject("SELECT amount FROM payments WHERE sale_id = ?", Double.class, sale)).isEqualTo(20.0);
        // 19.99 received is not enough for 20.00
        sell(item, 1, "CASH", "\"cashReceived\":19.99").andExpect(status().isBadRequest());
        // a card payment is never rounded
        long card = jsonLong(sell(item, 1, "CARD", null).andExpect(jsonPath("$.amountDue").value(19.97)).andReturn(), "saleId");
        assertThat(db.queryForObject("SELECT amount FROM payments WHERE sale_id = ?", Double.class, card)).isEqualTo(19.97);
    }

    @Test
    void roundingRule_nearestStep_midpointUp_everyAllowedStep_othersRefused() throws Exception {
        assertThat(Sale.cashRounding(1995, 10)).isEqualTo(5);   // 19.95 → 20.00
        assertThat(Sale.cashRounding(1994, 10)).isEqualTo(-4);  // 19.94 → 19.90
        assertThat(Sale.cashRounding(1997, 5)).isEqualTo(-2);   // → 19.95
        assertThat(Sale.cashRounding(1974, 50)).isEqualTo(-24); // → 19.50
        assertThat(Sale.cashRounding(1975, 50)).isEqualTo(25);  // → 20.00
        assertThat(Sale.cashRounding(1950, 100)).isEqualTo(50); // → 20.00
        assertThat(Sale.cashRounding(1949, 100)).isEqualTo(-49);
        assertThat(Sale.cashRounding(1997, 0)).isZero();
        for (int bad : new int[]{1, 7, 25, 200, -5}) {
            as(shop.adminToken(), put("/api/store/settings"), "{\"cashRoundingMinor\":" + bad + "}")
                    .andExpect(status().isBadRequest());
        }
        as(shop.cashierToken(), put("/api/store/settings"), "{\"cashRoundingMinor\":10}").andExpect(status().isForbidden());
    }

    @Test
    void resumedSuspendedInvoiceIsRoundedToo() throws Exception {
        step(10);
        long suspended = jsonLong(as(shop.cashierToken(), post("/api/sales/suspended"),
                "{\"items\":[{\"productId\":" + item + ",\"quantity\":1}]}").andExpect(status().isCreated()).andReturn(), "saleId");
        as(shop.cashierToken(), post("/api/sales"), "{\"saleId\":" + suspended + ",\"items\":[{\"productId\":" + item
                + ",\"quantity\":1}],\"paymentMethod\":\"CASH\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amountDue").value(20.0));
        assertThat(db.queryForObject("SELECT amount FROM payments WHERE sale_id = ?", Double.class, suspended)).isEqualTo(20.0);
    }

    @Test
    void refunds_roundedOnce_lastPaysTheRest_drawerAndReportsExact() throws Exception {
        step(10);
        long p = createProduct(shop.adminToken(), "Soap", "5012345678900", 3.33, 50);
        long drawer = jsonLong(as(shop.adminToken(), post("/api/drawers"), "{\"name\":\"Till\"}").andReturn(), "drawerId");
        long shift = jsonLong(as(shop.cashierToken(), post("/api/shifts"), "{\"drawerId\":" + drawer + ",\"openingCash\":100}")
                .andExpect(status().isCreated()).andReturn(), "shiftId");
        // 3 × 3.33 = 9.99 → pays 10.00
        long sale = jsonLong(sell(p, 3, "CASH", null).andExpect(jsonPath("$.amountDue").value(10.0)).andReturn(), "saleId");
        long line = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ?", Long.class, sale);
        List<Double> refunds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MvcResult r = as(shop.cashierToken(), post("/api/returns"), "{\"saleId\":" + sale + ",\"saleItemId\":" + line
                    + ",\"quantity\":1,\"reason\":\"Changed mind\"}").andExpect(status().isCreated()).andReturn();
            refunds.add(Double.parseDouble(r.getResponse().getContentAsString().replaceAll("(?s).*\"refundAmount\":([0-9.]+).*", "$1")));
        }
        // 3.33 → 3.30, 3.30, and the last pays what is left of 10.00: 3.40
        assertThat(refunds).containsExactly(3.3, 3.3, 3.4);
        assertThat(db.queryForObject("SELECT sum(refund_minor) FROM returns WHERE sale_id = ?", Long.class, sale)).isEqualTo(1000);
        assertThat(db.queryForObject("SELECT sum(rounding_minor) FROM returns WHERE sale_id = ?", Long.class, sale)).isEqualTo(1);
        // the drawer: 100 + 10.00 in − 10.00 out = 100.00, and the database accepts that closing
        as(shop.cashierToken(), get("/api/shifts/current"), null).andExpect(jsonPath("$.shift.expectedCash").value(100.0));
        as(shop.cashierToken(), post("/api/shifts/" + shift + "/close"), "{\"countedCash\":100}").andExpect(status().isOk());
        // reports: revenue 9.99, refunds 9.99 (exact shares): rounding is not revenue
        String day = LocalDate.now().toString();
        as(shop.adminToken(), get("/api/reports/summary?from=" + day + "&to=" + day), null)
                .andExpect(jsonPath("$.grossSales").value(9.99)).andExpect(jsonPath("$.refunds").value(9.99))
                .andExpect(jsonPath("$.netSales").value(0.0));
    }
}
