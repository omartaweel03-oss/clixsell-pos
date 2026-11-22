package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Activity log (Guardian Mode, decision D4): every event is written by the
 * backend with the LOGGED-IN user who performed it – never the cashier who
 * started an invoice and never a user sent by the app – in the same
 * transaction as the action, and also while Guardian Mode is off.
 */
class ActivityLogApiTest extends GuardianApiTestBase {

    private StoreSetup shini;
    private long ahmad, sara;
    private String ahmadToken, saraToken;
    private long milk;

    @BeforeEach
    void data() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        Object[] a = namedCashier(shini.adminToken(), "shini.ahmad", "Ahmad Odeh");
        Object[] s = namedCashier(shini.adminToken(), "shini.sara", "Sara Nasser");
        ahmad = (long) a[0];
        ahmadToken = (String) a[1];
        sara = (long) s[0];
        saraToken = (String) s[1];
        milk = product(shini.adminToken(), "Milk", "11111111", "dairy", 5, 3.2, 40, 5);
    }

    private List<Map<String, Object>> log(String type) {
        return db.queryForList("SELECT * FROM activity_log WHERE event_type = ? ORDER BY log_id", type);
    }

    @Test
    void theDiscountBelongsToTheUserWhoCompletedTheResumedInvoice() throws Exception {
        assertThat((Boolean) read(getJson(shini.adminToken(), "/api/guardian/status"), "$.enabled")).isFalse();
        long saleId = suspend(ahmadToken, Map.of(milk, 10), "PERCENT", 10.0); // Ahmad starts and suspends
        assertThat(log("DISCOUNT_APPLIED")).isEmpty(); // suspending is not a completed discount
        completeSale(saraToken, saleId, Map.of(milk, 10), "PERCENT", 10.0) // Sara resumes and completes
                .andExpect(status().isCreated());

        List<Map<String, Object>> rows = log("DISCOUNT_APPLIED");
        assertThat(rows).hasSize(1); // recorded while Guardian Mode is OFF
        assertThat(((Number) rows.get(0).get("user_id")).longValue()).isEqualTo(sara);
        assertThat(((Number) rows.get(0).get("store_id")).longValue()).isEqualTo(shini.storeId());
        assertThat(((Number) rows.get(0).get("entity_id")).longValue()).isEqualTo(saleId);
        assertThat((String) rows.get(0).get("details")).contains("\"startedByUserId\":" + ahmad)
                .contains("\"resumed\":true").contains("\"percent\":10.0").contains("\"maxPercentAtSale\":10.0");
        // The invoice itself still shows who started it (existing behaviour, unchanged).
        assertThat(db.queryForObject("SELECT cashier_id FROM sales WHERE sale_id = ?", Long.class, saleId)).isEqualTo(ahmad);

        setGuardian(shini.adminToken(), true);
        String alerts = getJson(shini.adminToken(), "/api/guardian/alerts?rule=G1");
        assertThat(read(alerts, "$.alerts[0].actor.name").toString()).isEqualTo("Sara Nasser");
        assertThat(read(alerts, "$.alerts[0].params.startedBy").toString()).isEqualTo("Ahmad Odeh");
    }

    @Test
    void aCancellationBelongsToTheUserWhoCanceled() throws Exception {
        long saleId = suspend(ahmadToken, Map.of(milk, 1), null, null);
        cancel(saraToken, saleId);
        List<Map<String, Object>> rows = log("INVOICE_CANCELED");
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("user_id")).longValue()).isEqualTo(sara);
        assertThat((String) rows.get(0).get("details")).contains("\"startedByUserId\":" + ahmad);
    }

    @Test
    void aUserSentByTheAppIsIgnored() throws Exception {
        mvc.perform(post("/api/sales").header("Authorization", bearer(saraToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":%d,"quantity":10}],"discountType":"PERCENT","discountValue":10,
                                 "paymentMethod":"CASH","cashierId":%d,"userId":%d,"storeId":999}
                                """.formatted(milk, ahmad, ahmad)))
                .andExpect(status().isCreated());
        List<Map<String, Object>> rows = log("DISCOUNT_APPLIED");
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("user_id")).longValue()).isEqualTo(sara);
        assertThat(((Number) rows.get(0).get("store_id")).longValue()).isEqualTo(shini.storeId());
    }

    @Test
    void aSaleWithoutDiscountOrAFailedSaleWritesNothing() throws Exception {
        sell(ahmadToken, Map.of(milk, 1), null, null);
        // Not enough stock: the sale fails, so no sale and no log row are saved.
        long before = db.queryForObject("SELECT COUNT(*) FROM sales", Long.class);
        completeSale(ahmadToken, null, Map.of(milk, 500), "PERCENT", 10.0).andExpect(status().is4xxClientError());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sales", Long.class)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activity_log", Long.class)).isZero();
    }

    @Test
    void priceCostStockChangesAndDeletionsAreLoggedOnlyWhenSomethingChanged() throws Exception {
        long admin = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.admin'", Long.class);
        String same = """
                {"name":"Milk","barcode":"11111111","category":"dairy","sellingPrice":5,"quantity":40,"minStock":5,
                 "supplierName":"Main Supplier","costPrice":3.2}""";
        mvc.perform(put("/api/products/" + milk).header("Authorization", bearer(shini.adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content(same)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activity_log", Long.class)).isZero();

        String changed = """
                {"name":"Milk","barcode":"11111111","category":"dairy","sellingPrice":4.5,"quantity":31,"minStock":5,
                 "supplierName":"Main Supplier","costPrice":3.4}""";
        mvc.perform(put("/api/products/" + milk).header("Authorization", bearer(shini.adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content(changed)).andExpect(status().isOk());
        assertThat(db.queryForList("SELECT event_type || ' ' || details FROM activity_log ORDER BY log_id", String.class))
                .containsExactly("PRICE_CHANGED {\"old\":5.0,\"new\":4.5}", "COST_CHANGED {\"old\":3.2,\"new\":3.4}",
                        "STOCK_ADJUSTED {\"old\":40,\"new\":31,\"change\":-9}");

        deleteProduct(shini.adminToken(), milk);
        List<Map<String, Object>> rows = log("PRODUCT_DELETED");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("entity_name")).isEqualTo("Milk");
        assertThat((String) rows.get(0).get("details")).contains("\"barcode\":\"11111111\"").contains("\"quantity\":31");
        assertThat(db.queryForList("SELECT DISTINCT user_id FROM activity_log", Long.class)).containsExactly(admin);
        // The cashier still cannot change or delete products (unchanged).
        mvc.perform(put("/api/products/" + milk).header("Authorization", bearer(ahmadToken))
                .contentType(MediaType.APPLICATION_JSON).content(changed)).andExpect(status().isForbidden());
    }

    @Test
    void theLogOfOneStoreIsNeverShownToAnother() throws Exception {
        StoreSetup neshan = setupStore("Neshan Market", "neshan");
        cancel(saraToken, suspend(ahmadToken, Map.of(milk, 1), null, null));
        setGuardian(neshan.adminToken(), true);
        String page = getJson(neshan.adminToken(), "/api/guardian/activity");
        assertThat(page).doesNotContain("INVOICE_CANCELED").doesNotContain("Sara Nasser");
        assertThat((Integer) read(page, "$.total")).isEqualTo(1); // its own GUARDIAN_ENABLED
    }
}
