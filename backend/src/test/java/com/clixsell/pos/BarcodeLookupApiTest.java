package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI Product Identification, Phase 1: GET /api/products/by-barcode — the camera's exact barcode lookup.
 * Read only, store from the session, Cashier and Store Admin, no cost price for Cashiers.
 */
class BarcodeLookupApiTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;

    @BeforeEach
    void stores() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
    }

    private ResultActions lookup(String token, String code, String format) throws Exception {
        var req = get("/api/products/by-barcode").param("code", code);
        if (format != null) req = req.param("format", format);
        if (token != null) req = req.header("Authorization", bearer(token));
        return mvc.perform(req);
    }

    private long count() {
        return db.queryForObject("SELECT COUNT(*) FROM products", Long.class);
    }

    @Test
    void anExistingBarcodeReturnsTheRealProductOfThisStore() throws Exception {
        long id = createProduct(shini.adminToken(), "Jericho Milk 1L", "4006381333931", 5.5, 12);
        lookup(shini.cashierToken(), "4006381333931", "EAN_13").andExpect(status().isOk())
                .andExpect(jsonPath("$.matchType").value("EXACT"))
                .andExpect(jsonPath("$.scannedCode").value("4006381333931"))
                .andExpect(jsonPath("$.matchedBarcode").value("4006381333931"))
                .andExpect(jsonPath("$.product.productId").value(id))
                .andExpect(jsonPath("$.product.name").value("Jericho Milk 1L"))
                .andExpect(jsonPath("$.product.sellingPrice").value(5.5))
                .andExpect(jsonPath("$.product.quantity").value(12));
    }

    @Test
    void aBarcodeMissingFromInventoryIs404AndNothingIsCreated() throws Exception {
        long before = count();
        lookup(shini.cashierToken(), "96385074", "EAN_8").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void valuesThatCannotBeProductBarcodesAreRefused() throws Exception {
        lookup(shini.cashierToken(), "ABC-123", "CODE_128").andExpect(status().isBadRequest());
        lookup(shini.cashierToken(), "1234567", null).andExpect(status().isBadRequest());
        lookup(shini.cashierToken(), "4006381333931", "QR_CODE").andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/by-barcode").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upcAAndEan13FormsOfTheSameNumberFindTheProduct() throws Exception {
        long id = createProduct(shini.adminToken(), "Imported Cola", "0036000291452", 3.0, 5);
        lookup(shini.cashierToken(), "036000291452", "UPC_A").andExpect(status().isOk())
                .andExpect(jsonPath("$.matchType").value("GTIN_EQUIVALENT"))
                .andExpect(jsonPath("$.scannedCode").value("036000291452"))
                .andExpect(jsonPath("$.matchedBarcode").value("0036000291452"))
                .andExpect(jsonPath("$.product.productId").value(id));
        long upcE = createProduct(shini.adminToken(), "Small Pack", "042100005264", 1.0, 3);
        lookup(shini.cashierToken(), "04252614", "UPC_E").andExpect(status().isOk())
                .andExpect(jsonPath("$.product.productId").value(upcE));
        // the same 8 digits as EAN-8 are a different number: not found
        lookup(shini.cashierToken(), "04252614", "EAN_8").andExpect(status().isNotFound());
    }

    @Test
    void cashiersNeverSeeTheCostStoreAdminsDo() throws Exception {
        createProduct(shini.adminToken(), "Jericho Milk 1L", "4006381333931", 5.5, 12);
        db.update("UPDATE products SET cost_price = 3.25 WHERE barcode = '4006381333931'");
        String cashier = lookup(shini.cashierToken(), "4006381333931", null).andReturn().getResponse().getContentAsString();
        assertThat(cashier).doesNotContain("3.25").doesNotContain("costPrice\":3");
        lookup(shini.adminToken(), "4006381333931", null).andExpect(jsonPath("$.product.costPrice").value(3.25));
    }

    @Test
    void twoStoresWithTheSameBarcodeEachSeeOnlyTheirOwnProduct() throws Exception {
        long s = createProduct(shini.adminToken(), "Shini Milk", "4006381333931", 5.5, 12);
        long n = createProduct(neshan.adminToken(), "Neshan Milk", "4006381333931", 6.0, 4);
        lookup(shini.cashierToken(), "4006381333931", null).andExpect(jsonPath("$.product.productId").value(s))
                .andExpect(jsonPath("$.product.name").value("Shini Milk"));
        lookup(neshan.cashierToken(), "4006381333931", null).andExpect(jsonPath("$.product.productId").value(n))
                .andExpect(jsonPath("$.product.name").value("Neshan Milk"));
        long onlyShini = createProduct(shini.adminToken(), "Only Shini", "96385074", 2.0, 1);
        lookup(neshan.cashierToken(), "96385074", null).andExpect(status().isNotFound());
        assertThat(onlyShini).isPositive();
    }

    @Test
    void rolesAreUnchangedSystemAdminAndAnonymousAreRefused() throws Exception {
        createProduct(shini.adminToken(), "Jericho Milk 1L", "4006381333931", 5.5, 12);
        lookup(sysadminToken(), "4006381333931", null).andExpect(status().isForbidden());
        lookup(null, "4006381333931", null).andExpect(status().isUnauthorized());
        lookup(shini.adminToken(), "4006381333931", null).andExpect(status().isOk());
    }

    @Test
    void anOutOfStockProductIsReturnedWithItsRealZeroQuantity() throws Exception {
        createProduct(shini.adminToken(), "Empty Shelf Item", "4006381333931", 5.5, 0);
        lookup(shini.cashierToken(), "4006381333931", null).andExpect(jsonPath("$.product.quantity").value(0));
    }

    /**
     * Final validation: every barcode the inventory accepts (Product.validate: digits only, 8–14,
     * no check-digit rule) is found by the camera lookup, whatever symbology printed it, including
     * internal numeric Code 128 labels and lengths that are not GTINs.
     */
    @Test
    void everyBarcodeTheInventoryAcceptsIsFoundByTheCamera() throws Exception {
        String[][] cases = {
                {"12345678", "EAN_8"},          // 8 digits, wrong EAN-8 check digit: still a valid inventory barcode
                {"123456789", "CODE_128"},      // 9 digits
                {"1000000042", "CODE_128"},     // 10-digit internal shelf label
                {"12345678901", "CODE_128"},    // 11 digits
                {"40063813339310", "CODE_128"}, // 14 digits
                {"4006381333931", null},        // no format reported
        };
        for (String[] c : cases) {
            long id = createProduct(shini.adminToken(), "Item " + c[0], c[0], 1.0, 3);
            lookup(shini.cashierToken(), c[0], c[1]).andExpect(status().isOk())
                    .andExpect(jsonPath("$.matchType").value("EXACT"))
                    .andExpect(jsonPath("$.matchedBarcode").value(c[0]))
                    .andExpect(jsonPath("$.product.productId").value(id));
        }
    }

    /** The inventory trims a barcode before checking and storing it; the lookup does the same. */
    @Test
    void surroundingSpacesAreTrimmedLikeTheInventoryDoes() throws Exception {
        long id = createProduct(shini.adminToken(), "Jericho Milk 1L", " 4006381333931 ", 5.5, 12);
        lookup(shini.cashierToken(), " 4006381333931\t", "EAN_13").andExpect(status().isOk())
                .andExpect(jsonPath("$.scannedCode").value("4006381333931"))
                .andExpect(jsonPath("$.product.productId").value(id));
        // spaces inside the value are not trimmed: still not a product barcode
        lookup(shini.cashierToken(), "4006 381333931", "EAN_13").andExpect(status().isBadRequest());
    }
}
