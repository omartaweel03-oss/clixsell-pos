package com.clixsell.pos.product;

import com.clixsell.pos.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests the whole chain for a logged-in Store Admin:
 * API → business logic → SQLite database.
 * After each operation the test reads the database directly (JdbcTemplate)
 * to prove the INSERT / UPDATE / DELETE really happened, in the right store.
 */
class ProductApiIntegrationTest extends ApiTestBase {

    private long storeId;
    private String token;

    @BeforeEach
    void loginAsStoreAdmin() throws Exception {
        StoreSetup store = setupStore("Shini Supermarket", "shini");
        storeId = store.storeId();
        token = store.adminToken();
    }

    private long insert(String name, String barcode) throws Exception {
        MvcResult r = mvc.perform(post("/api/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(name, barcode, 5, 24, 10, "Dairy Co")))
                .andExpect(status().isCreated())
                .andReturn();
        return jsonLong(r, "productId");
    }

    // ── INSERT ────────────────────────────────────────────────────────────

    @Test
    void insertSavesRowInTheAdminsStoreAndCreatesSupplier() throws Exception {
        mvc.perform(post("/api/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Milk 1L", "7290012345678", 5, 3, 10, "Dairy Co")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.productId").isNumber())
                .andExpect(jsonPath("$.storeId").value(storeId))
                .andExpect(jsonPath("$.stockStatus").value("LOW_STOCK"))
                .andExpect(jsonPath("$.stockValue").value(15.0))
                .andExpect(jsonPath("$.supplierName").value("Dairy Co"));

        Map<String, Object> row = db.queryForMap(
                "SELECT p.store_id, p.name, p.barcode, p.selling_price, p.quantity, p.min_stock, "
                        + "s.name AS supplier, s.store_id AS supplier_store "
                        + "FROM products p JOIN suppliers s ON s.supplier_id = p.supplier_id");
        assertThat(((Number) row.get("store_id")).longValue()).isEqualTo(storeId);
        assertThat(((Number) row.get("supplier_store")).longValue()).isEqualTo(storeId);
        assertThat(row).containsEntry("name", "Milk 1L")
                .containsEntry("barcode", "7290012345678")
                .containsEntry("selling_price", 5.0)
                .containsEntry("quantity", 3)
                .containsEntry("min_stock", 10)
                .containsEntry("supplier", "Dairy Co");
    }

    @Test
    void insertReusesExistingSupplierIgnoringCase() throws Exception {
        insert("Milk", "11111111");
        mvc.perform(post("/api/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Yogurt", "22222222", 3, 5, 1, "dairy co")))
                .andExpect(status().isCreated());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM suppliers", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(2);
    }

    @Test
    void insertWithDuplicateBarcodeIsRejectedAndNothingIsSaved() throws Exception {
        insert("Milk", "11111111");
        mvc.perform(post("/api/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Other", "11111111", 1, 1, 1, "Other Co")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BARCODE"))
                .andExpect(jsonPath("$.fieldErrors.barcode").exists());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(1);
    }

    @Test
    void insertWithInvalidDataReturnsFieldErrorsAndNothingIsSaved() throws Exception {
        mvc.perform(post("/api/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"barcode\":\"12ab\",\"category\":\"cars\",\"sellingPrice\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.barcode").exists())
                .andExpect(jsonPath("$.fieldErrors.category").exists())
                .andExpect(jsonPath("$.fieldErrors.sellingPrice").exists())
                .andExpect(jsonPath("$.fieldErrors.quantity").exists())
                .andExpect(jsonPath("$.fieldErrors.supplierName").exists());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM suppliers", Integer.class)).isZero();
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/api/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void imageIsStoredAndReturned() throws Exception {
        // "iVBORw0KGgo=" is base64 for the 8-byte PNG file signature
        mvc.perform(post("/api/products").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Milk","barcode":"33333333","category":"dairy","sellingPrice":5,
                                 "quantity":1,"minStock":0,"supplierName":"Co",
                                 "image":"iVBORw0KGgo=","imageName":"milk.png"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.image").value("iVBORw0KGgo="));
        byte[] stored = db.queryForObject("SELECT image FROM products", byte[].class);
        assertThat(stored).hasSize(8);
    }

    // ── SELECT ────────────────────────────────────────────────────────────

    @Test
    void listReturnsProductsNewestFirst() throws Exception {
        insert("First", "11111111");
        insert("Second", "22222222");
        mvc.perform(get("/api/products").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Second"))
                .andExpect(jsonPath("$[1].name").value("First"));
    }

    // ── UPDATE ────────────────────────────────────────────────────────────

    @Test
    void updateChangesTheRow() throws Exception {
        long id = insert("Milk", "11111111");
        mvc.perform(put("/api/products/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Milk 2L", "11111111", 7.5, 0, 10, "New Supplier")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Milk 2L"))
                .andExpect(jsonPath("$.stockStatus").value("OUT_OF_STOCK"));

        Map<String, Object> row = db.queryForMap(
                "SELECT p.name, p.selling_price, p.quantity, s.name AS supplier "
                        + "FROM products p JOIN suppliers s ON s.supplier_id = p.supplier_id WHERE p.product_id = ?", id);
        assertThat(row).containsEntry("name", "Milk 2L")
                .containsEntry("selling_price", 7.5)
                .containsEntry("quantity", 0)
                .containsEntry("supplier", "New Supplier");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isEqualTo(1);
    }

    @Test
    void updateToAnotherProductsBarcodeIsRejected() throws Exception {
        insert("Milk", "11111111");
        long bread = insert("Bread", "22222222");
        mvc.perform(put("/api/products/" + bread).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Bread", "11111111", 2, 5, 1, "Dairy Co")))
                .andExpect(status().isConflict());
        assertThat(db.queryForObject("SELECT barcode FROM products WHERE product_id = ?", String.class, bread))
                .isEqualTo("22222222");
    }

    @Test
    void updateMissingProductIsNotFound() throws Exception {
        mvc.perform(put("/api/products/999").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Milk", "11111111", 5, 1, 1, "Co")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    // ── DELETE ────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRow() throws Exception {
        long keep = insert("Milk", "11111111");
        long remove = insert("Bread", "22222222");
        mvc.perform(delete("/api/products/" + remove).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        assertThat(db.queryForList("SELECT product_id FROM products", Long.class)).containsExactly(keep);
        mvc.perform(get("/api/products/" + remove).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteMissingProductIsNotFound() throws Exception {
        mvc.perform(delete("/api/products/999").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    // ── Other ─────────────────────────────────────────────────────────────

    @Test
    void generateBarcodeReturnsUnusedEan13() throws Exception {
        mvc.perform(get("/api/products/generate-barcode").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.barcode").value(org.hamcrest.Matchers.matchesPattern("729\\d{10}")));
    }

    @Test
    void browserAppOnLocalhostIsAllowedByCors() throws Exception {
        mvc.perform(options("/api/products/1")
                        .header("Origin", "http://localhost:51234")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:51234"));
    }
}
