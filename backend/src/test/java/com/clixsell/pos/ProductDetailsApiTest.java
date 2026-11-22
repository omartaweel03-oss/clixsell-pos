package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Product Details (report Figure 3.4, UR-M5 / SR18) uses the existing
 * GET /api/products/{id}: own store only, cost price for Store Admins only,
 * current quantity after sales and returns. No new backend code.
 */
class ProductDetailsApiTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;
    private long milk;

    @BeforeEach
    void data() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
        milk = createProduct(shini.adminToken(), "Milk", "11111111", 5.00, 24);
        // purchase cost (Store Admin)
        mvc.perform(put("/api/products/" + milk).header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Milk","barcode":"11111111","category":"dairy","sellingPrice":5,
                                 "quantity":24,"minStock":10,"supplierName":"Dairy Co","costPrice":3.2}
                                """))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions details(String token) throws Exception {
        return mvc.perform(get("/api/products/" + milk).header("Authorization", bearer(token)));
    }

    @Test
    void storeAdminGetsEveryFieldOfTheFigureAndTheCost() throws Exception {
        details(shini.adminToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(milk))
                .andExpect(jsonPath("$.name").value("Milk"))
                .andExpect(jsonPath("$.barcode").value("11111111"))
                .andExpect(jsonPath("$.category").exists())
                .andExpect(jsonPath("$.sellingPrice").value(5.0))
                .andExpect(jsonPath("$.quantity").value(24))
                .andExpect(jsonPath("$.minStock").value(10))
                .andExpect(jsonPath("$.supplierName").value("Dairy Co"))
                .andExpect(jsonPath("$.stockStatus").value("IN_STOCK"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.costPrice").value(3.2));
    }

    @Test
    void cashierNeverReceivesTheCost() throws Exception {
        details(shini.cashierToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Milk"))
                .andExpect(jsonPath("$.costPrice").value(nullValue()));
    }

    @Test
    void otherStoreAndOtherRolesCannotSeeIt() throws Exception {
        details(neshan.adminToken()).andExpect(status().isNotFound());
        details(neshan.cashierToken()).andExpect(status().isNotFound());
        details(sysadminToken()).andExpect(status().isForbidden());
        mvc.perform(get("/api/products/" + milk)).andExpect(status().isUnauthorized());
    }

    @Test
    void quantityAndStatusAreCurrentAfterSaleAndReturn() throws Exception {
        MvcResult sale = mvc.perform(post("/api/sales").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":%d,\"quantity\":15}],\"paymentMethod\":\"CASH\"}"
                                .formatted(milk)))
                .andExpect(status().isCreated()).andReturn();
        details(shini.adminToken())
                .andExpect(jsonPath("$.quantity").value(9))
                .andExpect(jsonPath("$.stockStatus").value("LOW_STOCK"));

        long saleId = jsonLong(sale, "saleId");
        long itemId = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ?", Long.class, saleId);
        mvc.perform(post("/api/returns").header("Authorization", bearer(shini.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":2,\"reason\":\"Damaged\"}"
                                .formatted(saleId, itemId)))
                .andExpect(status().isCreated());
        details(shini.adminToken())
                .andExpect(jsonPath("$.quantity").value(11))
                .andExpect(jsonPath("$.stockStatus").value("IN_STOCK"));
    }

    @Test
    void deletedProductIsNotFoundAndOnlyTheStoreAdminMayDelete() throws Exception {
        mvc.perform(delete("/api/products/" + milk).header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/" + milk).header("Authorization", bearer(neshan.adminToken())))
                .andExpect(status().isNotFound());
        details(shini.adminToken()).andExpect(status().isOk());
        mvc.perform(delete("/api/products/" + milk).header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isNoContent());
        details(shini.adminToken()).andExpect(status().isNotFound());
    }
}
