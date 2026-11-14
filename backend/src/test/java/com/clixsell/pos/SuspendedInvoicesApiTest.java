package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspended Invoices screen (Figure 3.6, UR-C8 / UR-M10, SR9 / SR23): the
 * existing API it uses, with the existing rules, through the real database.
 * <ul>
 *   <li>GET /api/sales/suspended – only SUSPENDED invoices of the user's store, newest first,
 *       with what the screen shows (number, cashier, total, item count, date)</li>
 *   <li>DELETE /api/sales/suspended/{id} – "delete" = CANCELED, the record is kept</li>
 *   <li>Cashier and Store Admin allowed; System Admin and other stores refused</li>
 * </ul>
 */
class SuspendedInvoicesApiTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;
    private long milk;     // 5.00, stock 10
    private long bread;    // 2.00, stock 10

    private MvcResult suspend(String token, String items) throws Exception {
        return mvc.perform(post("/api/sales/suspended").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":" + items + "}"))
                .andExpect(status().isCreated()).andReturn();
    }

    @BeforeEach
    void data() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
        milk = createProduct(shini.adminToken(), "Milk", "11111111", 5.00, 10);
        bread = createProduct(shini.adminToken(), "Bread", "22222222", 2.00, 10);
        long juice = createProduct(neshan.adminToken(), "Juice", "33333333", 8.00, 5);
        suspend(neshan.cashierToken(), "[{\"productId\":%d,\"quantity\":1}]".formatted(juice));
    }

    @Test
    void listShowsOnlySuspendedInvoicesOfTheStoreWithTheScreenColumns() throws Exception {
        MvcResult first = suspend(shini.cashierToken(),
                "[{\"productId\":%d,\"quantity\":2},{\"productId\":%d,\"quantity\":1}]".formatted(milk, bread));
        MvcResult second = suspend(shini.adminToken(), "[{\"productId\":%d,\"quantity\":1}]".formatted(bread));
        // A completed sale is not listed.
        mvc.perform(post("/api/sales").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":1}],\"paymentMethod\":\"CASH\"}".formatted(milk)))
                .andExpect(status().isCreated());

        for (String token : new String[]{shini.cashierToken(), shini.adminToken()}) {
            mvc.perform(get("/api/sales/suspended").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    // newest first
                    .andExpect(jsonPath("$[0].saleId").value(jsonLong(second, "saleId")))
                    .andExpect(jsonPath("$[1].saleId").value(jsonLong(first, "saleId")))
                    .andExpect(jsonPath("$[1].invoiceNumber").value("INV-000001"))
                    .andExpect(jsonPath("$[1].status").value("SUSPENDED"))
                    .andExpect(jsonPath("$[1].cashierName").value("Test User"))
                    .andExpect(jsonPath("$[1].itemCount").value(2))
                    .andExpect(jsonPath("$[1].total").value(13.92))   // (10 + 2) + 16 %
                    .andExpect(jsonPath("$[1].createdAt").exists());
        }
        // Suspended invoices reserve no stock: only the completed sale changed it.
        assertThat(db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, milk))
                .isEqualTo(9);
    }

    @Test
    void deleteMarksCanceledKeepsTheRecordAndItCannotBeResumed() throws Exception {
        long id = jsonLong(suspend(shini.cashierToken(), "[{\"productId\":%d,\"quantity\":2}]".formatted(milk)),
                "saleId");
        mvc.perform(delete("/api/sales/suspended/" + id).header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
        // Kept in the database with its lines, no longer listed.
        assertThat(db.queryForObject("SELECT status FROM sales WHERE sale_id = ?", String.class, id))
                .isEqualTo("CANCELED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sale_items WHERE sale_id = ?", Integer.class, id))
                .isEqualTo(1);
        mvc.perform(get("/api/sales/suspended").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(jsonPath("$", hasSize(0)));
        // Completing (resuming) or deleting it again is refused.
        mvc.perform(post("/api/sales").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"items\":[{\"productId\":%d,\"quantity\":2}],\"paymentMethod\":\"CASH\"}"
                                .formatted(id, milk)))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/sales/suspended/" + id).header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, milk))
                .isEqualTo(10);
    }

    @Test
    void resumeAndCompleteKeepsTheNumberAndLeavesTheList() throws Exception {
        MvcResult s = suspend(shini.cashierToken(), "[{\"productId\":%d,\"quantity\":3}]".formatted(milk));
        long id = jsonLong(s, "saleId");
        mvc.perform(get("/api/sales/" + id).header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].quantity").value(3));
        mvc.perform(post("/api/sales").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"items\":[{\"productId\":%d,\"quantity\":3}],\"paymentMethod\":\"CARD\"}"
                                .formatted(id, milk)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceNumber").value(jsonString(s, "invoiceNumber")))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/sales/suspended").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void storesAreSeparatedAndOnlyStoreUsersHaveAccess() throws Exception {
        long id = jsonLong(suspend(shini.cashierToken(), "[{\"productId\":%d,\"quantity\":1}]".formatted(milk)),
                "saleId");
        mvc.perform(get("/api/sales/suspended").header("Authorization", bearer(neshan.adminToken())))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].items[0].productName").value("Juice"));
        // Another store cannot open, delete or complete Shini's invoice.
        mvc.perform(get("/api/sales/" + id).header("Authorization", bearer(neshan.cashierToken())))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/sales/suspended/" + id).header("Authorization", bearer(neshan.adminToken())))
                .andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT status FROM sales WHERE sale_id = ?", String.class, id))
                .isEqualTo("SUSPENDED");
        // System Admin and anonymous requests are refused.
        mvc.perform(get("/api/sales/suspended").header("Authorization", bearer(sysadminToken())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/sales/suspended")).andExpect(status().isUnauthorized());
    }
}
