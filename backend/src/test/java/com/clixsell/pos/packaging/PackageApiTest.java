package com.clixsell.pos.packaging;

import com.clixsell.pos.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feature 6 (scenario B): piece / carton pricing with inventory in pieces, optional carton barcode,
 * whole-carton returns at the price paid, and reports that count pieces.
 */
class PackageApiTest extends ApiTestBase {

    private StoreSetup shop;
    private StoreSetup other;
    private long water; // 2.00 a bottle, 100 in stock
    private long carton; // 24 bottles for 40.00

    @BeforeEach
    void setUp() throws Exception {
        shop = setupStore("Pack Store", "pack");
        other = setupStore("Pack Other", "packother");
        as(shop.adminToken(), put("/api/store/settings"), "{\"taxRate\":0}").andExpect(status().isOk());
        water = createProduct(shop.adminToken(), "Water 0.5L", "4006381333931", 2.00, 100);
        db.update("UPDATE products SET cost_price = 1.25 WHERE product_id = ?", water);
        carton = jsonLong(as(shop.adminToken(), post("/api/products/" + water + "/packages"),
                "{\"name\":\"Carton 24\",\"unitsPerPackage\":24,\"price\":40,\"barcode\":\"7290000000244\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.price").value(40.0)).andReturn(), "packageId");
    }

    private ResultActions as(String token, MockHttpServletRequestBuilder r, String body) throws Exception {
        if (body != null) r = r.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(r.header("Authorization", bearer(token)));
    }

    private ResultActions sell(String token, String lines) throws Exception {
        return as(token, post("/api/sales"), "{\"items\":[" + lines + "],\"paymentMethod\":\"CASH\"}");
    }

    private String cartons(int n) {
        return "{\"productId\":" + water + ",\"quantity\":" + n + ",\"packageId\":" + carton + "}";
    }

    private String pieces(int n) {
        return "{\"productId\":" + water + ",\"quantity\":" + n + "}";
    }

    private int stock() {
        return db.queryForObject("SELECT quantity FROM products WHERE product_id = ?", Integer.class, water);
    }

    @Test
    void scenarioB_cartonAndPiecesOnOneInvoice_stockInPieces_snapshotOnTheLine() throws Exception {
        long sale = jsonLong(sell(shop.cashierToken(), cartons(2) + "," + pieces(3)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(86.0)) // 2 × 40 + 3 × 2
                .andExpect(jsonPath("$.items[0].packageName").value("Carton 24"))
                .andExpect(jsonPath("$.items[0].unitsPerPackage").value(24))
                .andExpect(jsonPath("$.items[1].packageId").doesNotExist())
                .andReturn(), "saleId");
        assertThat(stock()).isEqualTo(100 - 48 - 3);
        assertThat(db.queryForMap("SELECT quantity, unit_price, line_total, units_per_package, package_name, unit_cost, "
                + "unit_price_minor FROM sale_items WHERE sale_id = ? AND package_id IS NOT NULL", sale))
                .containsEntry("quantity", 2).containsEntry("unit_price", 40.0).containsEntry("line_total", 80.0)
                .containsEntry("units_per_package", 24).containsEntry("package_name", "Carton 24")
                .containsEntry("unit_cost", 30.0) // 24 × 1.25: profit stays right
                .containsEntry("unit_price_minor", 4000);

        // later price change and deactivation never change the invoice; the size never changes
        as(shop.adminToken(), patch("/api/packages/" + carton), "{\"price\":45,\"active\":false}").andExpect(status().isOk());
        as(shop.adminToken(), patch("/api/packages/" + carton), "{\"unitsPerPackage\":12}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PACKAGE_SIZE_FIXED"));
        assertThat(db.queryForObject("SELECT unit_price FROM sale_items WHERE sale_id = ? AND package_id IS NOT NULL",
                Double.class, sale)).isEqualTo(40.0);
        sell(shop.cashierToken(), cartons(1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PACKAGE_INACTIVE"));

        // reports count pieces: 48 + 3 = 51
        as(shop.adminToken(), get("/api/reports/summary?from=" + java.time.LocalDate.now() + "&to=" + java.time.LocalDate.now()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.unitsSold").value(51));
    }

    @Test
    void stockCheckAddsPiecesAndCartonsOfTheSameProduct() throws Exception {
        db.update("UPDATE products SET quantity = 30 WHERE product_id = ?", water);
        sell(shop.cashierToken(), cartons(1) + "," + pieces(7)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(stock()).isEqualTo(30);
        sell(shop.cashierToken(), cartons(1) + "," + pieces(6)).andExpect(status().isCreated());
        assertThat(stock()).isZero();
    }

    @Test
    void returns_wholeCartonsOnly_atThePricePaid_piecesBackIntoStock() throws Exception {
        long sale = jsonLong(sell(shop.cashierToken(), cartons(2)).andReturn(), "saleId");
        long line = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ?", Long.class, sale);
        as(shop.adminToken(), patch("/api/packages/" + carton), "{\"price\":50}").andExpect(status().isOk());
        as(shop.cashierToken(), post("/api/returns"), "{\"saleId\":" + sale + ",\"saleItemId\":" + line
                + ",\"quantity\":1,\"reason\":\"Damaged\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refundAmount").value(40.0)) // the price paid, not today's 50
                .andExpect(jsonPath("$.stillReturnable").value(1));
        assertThat(stock()).isEqualTo(100 - 48 + 24);
        as(shop.cashierToken(), post("/api/returns"), "{\"saleId\":" + sale + ",\"saleItemId\":" + line
                + ",\"quantity\":2,\"reason\":\"Again\"}").andExpect(status().isBadRequest());
        // net pieces for purchase recommendations / top products: 48 − 24
        as(shop.adminToken(), get("/api/reports/top-products?from=" + java.time.LocalDate.now() + "&to=" + java.time.LocalDate.now()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$..units").value(org.hamcrest.Matchers.hasItem(24)));
    }

    @Test
    void barcodes_optional_uniqueAcrossProductsAndPackages_validated() throws Exception {
        as(shop.adminToken(), post("/api/products/" + water + "/packages"), "{\"name\":\"Box 6\",\"unitsPerPackage\":6,\"price\":11.5}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.barcode").doesNotExist());
        as(shop.adminToken(), post("/api/products/" + water + "/packages"),
                "{\"name\":\"Box 12\",\"unitsPerPackage\":12,\"price\":22,\"barcode\":\"4006381333931\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_BARCODE"));
        mvc.perform(post("/api/products").header("Authorization", bearer(shop.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Juice", "7290000000244", 3, 5, 1, "Main Supplier")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_BARCODE"));
        for (String bad : new String[]{"{\"name\":\"X\",\"unitsPerPackage\":1,\"price\":5}",
                "{\"name\":\"X\",\"unitsPerPackage\":6,\"price\":0}",
                "{\"name\":\"X\",\"unitsPerPackage\":6,\"price\":1.001}",
                "{\"name\":\"\",\"unitsPerPackage\":6,\"price\":5}",
                "{\"name\":\"X\",\"unitsPerPackage\":6,\"price\":5,\"barcode\":\"12ab\"}"}) {
            as(shop.adminToken(), post("/api/products/" + water + "/packages"), bad).andExpect(status().isBadRequest());
        }
        // the other store may use the same barcode for its own products
        createProduct(other.adminToken(), "Their Water", "7290000000244", 2, 5);
    }

    @Test
    void permissionsAndIsolation() throws Exception {
        as(shop.cashierToken(), get("/api/packages"), null).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Carton 24"));
        as(shop.cashierToken(), post("/api/products/" + water + "/packages"), "{\"name\":\"X\",\"unitsPerPackage\":6,\"price\":5}")
                .andExpect(status().isForbidden());
        as(shop.cashierToken(), patch("/api/packages/" + carton), "{\"price\":1}").andExpect(status().isForbidden());
        as(other.adminToken(), get("/api/packages"), null).andExpect(jsonPath("$.length()").value(0));
        as(other.adminToken(), patch("/api/packages/" + carton), "{\"price\":1}").andExpect(status().isNotFound());
        long theirs = createProduct(other.adminToken(), "Their Water", "5012345678900", 2, 50);
        as(other.cashierToken(), post("/api/sales"), "{\"items\":[{\"productId\":" + theirs + ",\"quantity\":1,\"packageId\":"
                + carton + "}],\"paymentMethod\":\"CASH\"}").andExpect(status().isNotFound());
        // a package of ANOTHER product of the same store is refused too
        long juice = createProduct(shop.adminToken(), "Juice", "5000000000017", 3, 50);
        sell(shop.cashierToken(), "{\"productId\":" + juice + ",\"quantity\":1,\"packageId\":" + carton + "}")
                .andExpect(status().isNotFound());
    }
}
