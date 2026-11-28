package com.clixsell.pos.sale;

import com.clixsell.pos.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POS store upgrade, feature 3: cash amount received and change. The server checks the amount
 * against the total it calculates itself; the payment is recorded at the invoice total (change is
 * never revenue); an amount that is too small completes nothing.
 */
class CashPaymentApiTest extends ApiTestBase {

    private StoreSetup shop;
    private long water; // 31.90 + 16% tax = 37.00

    @BeforeEach
    void setUp() throws Exception {
        shop = setupStore("Cash Store", "cash");
        water = createProduct(shop.adminToken(), "Water Pack", "4006381333931", 31.90, 10);
    }

    private ResultActions sell(String method, String cashReceived) throws Exception {
        String body = "{\"items\":[{\"productId\":" + water + ",\"quantity\":1}],\"paymentMethod\":\"" + method + "\""
                + (cashReceived == null ? "" : ",\"cashReceived\":" + cashReceived) + "}";
        return mvc.perform(post("/api/sales").header("Authorization", bearer(shop.cashierToken()))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int stock() {
        return db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, water);
    }

    private long count(String table) {
        return db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    void overpaymentGivesTheChangeAndRecordsOnlyTheTotal() throws Exception {
        long id = jsonLong(sell("CASH", "50").andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(37.0))
                .andExpect(jsonPath("$.cashReceived").value(50.0))
                .andExpect(jsonPath("$.changeDue").value(13.0))
                .andReturn(), "saleId");
        // one payment, at the invoice total — the 13.00 change is not revenue
        assertThat(db.queryForList("SELECT amount FROM payments WHERE sale_id = ?", Double.class, id))
                .containsExactly(37.0);
        assertThat(db.queryForObject("SELECT total FROM sales WHERE sale_id = ?", Double.class, id)).isEqualTo(37.0);
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void exactAmountGivesZeroChange() throws Exception {
        sell("CASH", "37").andExpect(status().isCreated()).andExpect(jsonPath("$.changeDue").value(0.0));
        sell("CASH", "37.00").andExpect(status().isCreated()).andExpect(jsonPath("$.changeDue").value(0.0));
    }

    @Test
    void anInsufficientAmountCompletesNothing() throws Exception {
        long sales = count("sales"), payments = count("payments");
        sell("CASH", "20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.cashReceived").value("The amount received is 20.00; still to pay: 17.00"));
        sell("CASH", "36.99").andExpect(status().isBadRequest());
        assertThat(count("sales")).isEqualTo(sales);
        assertThat(count("payments")).isEqualTo(payments);
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void zeroNegativeAndSubCentAmountsAreRefused() throws Exception {
        for (String bad : new String[]{"0", "-5", "50.001"}) {
            sell("CASH", bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.cashReceived").exists());
        }
        assertThat(stock()).isEqualTo(10);
    }

    @Test
    void theAmountReceivedIsOnlyForCash() throws Exception {
        sell("CARD", "50").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.cashReceived").exists());
        assertThat(stock()).isEqualTo(10);
        // other methods are unchanged
        sell("CARD", null).andExpect(status().isCreated()).andExpect(jsonPath("$.changeDue").doesNotExist());
        sell("WALLET", null).andExpect(status().isCreated());
        sell("BANK_TRANSFER", null).andExpect(status().isCreated());
    }

    @Test
    void cashWithoutAnAmountStillWorksAsBefore() throws Exception {
        sell("CASH", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(37.0))
                .andExpect(jsonPath("$.cashReceived").doesNotExist());
    }

    @Test
    void decimalAmountsAreExact() throws Exception {
        // 37.00 from 40.10 = 3.10 exactly (no binary floating-point error)
        sell("CASH", "40.10").andExpect(status().isCreated()).andExpect(jsonPath("$.changeDue").value(3.1));
    }
}
