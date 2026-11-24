package com.clixsell.pos.purchase;

import com.clixsell.pos.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Supplier Management API (Purchase Orders Phase 1) with two real stores through the
 * real application: Store Admin only, strict bodies, name and WhatsApp validation,
 * activation / deactivation, store isolation, and the existing Product / POS
 * behavior with suppliers (D16, D26).
 */
class SupplierApiTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;

    @BeforeEach
    void stores() throws Exception {
        shini = setupStore("Shini", "shini");
        neshan = setupStore("Neshan", "neshan");
    }

    private ResultActions call(String method, String url, String token, String body) throws Exception {
        var req = switch (method) {
            case "GET" -> get(url);
            case "POST" -> post(url);
            case "PUT" -> put(url);
            default -> throw new IllegalArgumentException(method);
        };
        if (token != null) req = req.header("Authorization", bearer(token));
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req);
    }

    private long createSupplier(String token, String json) throws Exception {
        MvcResult r = call("POST", "/api/suppliers", token, json).andExpect(status().isCreated()).andReturn();
        return jsonLong(r, "supplierId");
    }

    private ResultActions product(String token, String method, String url, String name, String barcode, String supplier)
            throws Exception {
        return call(method, url, token, productJson(name, barcode, 5, 10, 2, supplier));
    }

    // ── management ──────────────────────────────────────────────────────────────────

    @Test
    void aStoreAdminAddsEditsListsAndViewsSuppliers() throws Exception {
        long id = createSupplier(shini.adminToken(), """
                {"name":"  Al   Quds   Dairy ","phone":"02-2345678","whatsappNumber":"+970 59-912 3456","address":"Nablus"}""");
        call("GET", "/api/suppliers/" + id, shini.adminToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Al Quds Dairy"))          // normalized
                .andExpect(jsonPath("$.whatsappNumber").value("970599123456"))  // digits only
                .andExpect(jsonPath("$.phone").value("02-2345678"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.productCount").value(0));

        product(shini.adminToken(), "POST", "/api/products", "Milk", "11111111", "Al Quds Dairy").andExpect(status().isCreated());
        call("GET", "/api/suppliers", shini.adminToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.supplierId == " + id + ")].productCount").value(1));
        call("GET", "/api/suppliers/" + id, shini.adminToken(), null)
                .andExpect(jsonPath("$.products", hasSize(1))).andExpect(jsonPath("$.products[0].name").value("Milk"));

        call("PUT", "/api/suppliers/" + id, shini.adminToken(), """
                {"name":"Al Quds Dairy Co","phone":null,"whatsappNumber":"00970599123457","address":""}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Al Quds Dairy Co"))
                .andExpect(jsonPath("$.whatsappNumber").value("970599123457"))
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist());
        // the product still points to the same supplier, now shown with the new name
        assertThat(db.queryForObject("SELECT s.name FROM products p JOIN suppliers s ON s.supplier_id = p.supplier_id "
                + "WHERE p.barcode = '11111111'", String.class)).isEqualTo("Al Quds Dairy Co");
    }

    @Test
    void namesAndNumbersAreValidated() throws Exception {
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"A\"}").andExpect(status().isBadRequest());
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"" + "x".repeat(81) + "\"}")
                .andExpect(status().isBadRequest());
        for (String bad : new String[]{"0599123456", "12345", "97059abc1234", "9705991234567890", "+0970599123456"}) {
            call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"Bad Co\",\"whatsappNumber\":\"" + bad + "\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.whatsappNumber").exists());
        }
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"Bad Co\",\"phone\":\"call me\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.phone").exists());
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"Bad Co\",\"address\":\"" + "a".repeat(201) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.address").exists());
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":123}").andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM suppliers", Long.class)).isZero();
        // valid international forms
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"Good Co\",\"whatsappNumber\":\"(970) 599.123.456\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.whatsappNumber").value("970599123456"));
    }

    @Test
    void duplicateNamesAreRefusedWithinAStoreOnly() throws Exception {
        long dairy = createSupplier(shini.adminToken(), "{\"name\":\"Dairy Co\"}");
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"  dairy   CO \"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SUPPLIER"));
        long bakery = createSupplier(shini.adminToken(), "{\"name\":\"Bakery Co\"}");
        call("PUT", "/api/suppliers/" + bakery, shini.adminToken(), "{\"name\":\"DAIRY co\"}")
                .andExpect(status().isConflict());
        createSupplier(neshan.adminToken(), "{\"name\":\"Dairy Co\"}"); // another store: allowed
        call("PUT", "/api/suppliers/" + dairy, shini.adminToken(), "{\"name\":\"Dairy Co\",\"phone\":\"0599 123 456\"}")
                .andExpect(status().isOk()); // keeping its own name is fine
    }

    @Test
    void aLegacyNameIsKeptWhenSentBackUnchanged() throws Exception {
        db.update("INSERT INTO suppliers (store_id, name) VALUES (?, 'Al  Quds')", shini.storeId());
        long id = db.queryForObject("SELECT supplier_id FROM suppliers WHERE name = 'Al  Quds'", Long.class);
        call("PUT", "/api/suppliers/" + id, shini.adminToken(), "{\"name\":\"Al  Quds\",\"whatsappNumber\":\"970599000001\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Al  Quds"));
        call("PUT", "/api/suppliers/" + id, shini.adminToken(), "{\"name\":\"Al   Quds Co\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Al Quds Co")); // a real change is normalized
    }

    @Test
    void bodiesAreStrict() throws Exception {
        call("POST", "/api/suppliers", shini.adminToken(), "{\"name\":\"Dairy Co\",\"storeId\":" + neshan.storeId() + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
                .andExpect(jsonPath("$.fieldErrors.storeId").exists());
        long id = createSupplier(shini.adminToken(), "{\"name\":\"Dairy Co\"}");
        call("PUT", "/api/suppliers/" + id, shini.adminToken(), "{\"name\":\"Dairy Co\",\"active\":false}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        call("PUT", "/api/suppliers/" + id + "/status", shini.adminToken(), "{\"active\":false,\"name\":\"X\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
        call("PUT", "/api/suppliers/" + id + "/status", shini.adminToken(), "{\"active\":\"no\"}")
                .andExpect(status().isBadRequest());
        call("PUT", "/api/suppliers/" + id + "/status", shini.adminToken(), "{}").andExpect(status().isBadRequest());
        call("POST", "/api/suppliers", shini.adminToken(), "[1,2]").andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT active FROM suppliers WHERE supplier_id = ?", Integer.class, id)).isEqualTo(1);
    }

    // ── security and isolation ──────────────────────────────────────────────────────

    @Test
    void onlyTheStoreAdminOfTheStoreCanManageItsSuppliers() throws Exception {
        long id = createSupplier(shini.adminToken(), "{\"name\":\"Dairy Co\",\"whatsappNumber\":\"970599000001\"}");
        String body = "{\"name\":\"Hacked\"}";
        // the other store's admin: "not found", nothing changes
        call("GET", "/api/suppliers/" + id, neshan.adminToken(), null).andExpect(status().isNotFound());
        call("PUT", "/api/suppliers/" + id, neshan.adminToken(), body).andExpect(status().isNotFound());
        call("PUT", "/api/suppliers/" + id + "/status", neshan.adminToken(), "{\"active\":false}")
                .andExpect(status().isNotFound());
        call("GET", "/api/suppliers", neshan.adminToken(), null).andExpect(jsonPath("$", hasSize(0)));
        // cashiers and the system admin: forbidden; no token: 401
        String sys = sysadminToken();
        for (String token : new String[]{shini.cashierToken(), sys}) {
            call("GET", "/api/suppliers", token, null).andExpect(status().isForbidden());
            call("GET", "/api/suppliers/" + id, token, null).andExpect(status().isForbidden());
            call("POST", "/api/suppliers", token, body).andExpect(status().isForbidden());
            call("PUT", "/api/suppliers/" + id, token, body).andExpect(status().isForbidden());
            call("PUT", "/api/suppliers/" + id + "/status", token, "{\"active\":false}").andExpect(status().isForbidden());
        }
        call("GET", "/api/suppliers", null, null).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT name || '|' || active FROM suppliers WHERE supplier_id = ?", String.class, id))
                .isEqualTo("Dairy Co|1");
        // a cashier still sees products with the supplier NAME only (as before), never its WhatsApp number
        product(shini.adminToken(), "POST", "/api/products", "Milk", "11111111", "Dairy Co").andExpect(status().isCreated());
        String products = call("GET", "/api/products", shini.cashierToken(), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(products).contains("Dairy Co").doesNotContain("970599000001");
    }

    @Test
    void suppliersCannotBeDeletedEvenWithDirectSql() throws Exception {
        long id = createSupplier(shini.adminToken(), "{\"name\":\"Unused Co\"}");
        assertThatThrownBy(() -> db.update("DELETE FROM suppliers WHERE supplier_id = ?", id))
                .hasMessageContaining("SUPPLIER_NO_DELETE");
        call("PUT", "/api/suppliers/" + id, shini.adminToken(), null).andExpect(status().isBadRequest());
    }

    // ── activation and the existing Product / POS features ─────────────────────────

    @Test
    void anInactiveSupplierIsNeverReactivatedByTheProductScreen() throws Exception {
        long id = createSupplier(shini.adminToken(), "{\"name\":\"Dairy Co\"}");
        long milk = jsonLong(product(shini.adminToken(), "POST", "/api/products", "Milk", "11111111", "Dairy Co")
                .andExpect(status().isCreated()).andReturn(), "productId");
        long bread = jsonLong(product(shini.adminToken(), "POST", "/api/products", "Bread", "22222222", "Bakery Co")
                .andExpect(status().isCreated()).andReturn(), "productId");
        call("PUT", "/api/suppliers/" + id + "/status", shini.adminToken(), "{\"active\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        // a NEW product with the inactive supplier → refused, supplier stays inactive
        product(shini.adminToken(), "POST", "/api/products", "Labneh", "33333333", "dairy co")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"))
                .andExpect(jsonPath("$.fieldErrors.supplierName").exists());
        // moving another product to it → refused
        product(shini.adminToken(), "PUT", "/api/products/" + bread, "Bread", "22222222", "Dairy Co")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
        // editing a product that already uses it (supplier unchanged) → allowed
        product(shini.adminToken(), "PUT", "/api/products/" + milk, "Milk 1L", "11111111", "Dairy Co")
                .andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT active FROM suppliers WHERE supplier_id = ?", Integer.class, id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM suppliers WHERE store_id = ?", Long.class, shini.storeId()))
                .as("no near-duplicate supplier was created").isEqualTo(2);

        // POS keeps selling the product of an inactive supplier
        call("POST", "/api/sales", shini.cashierToken(), """
                {"items":[{"productId":%d,"quantity":1}],"paymentMethod":"CASH"}""".formatted(milk))
                .andExpect(status().isCreated());

        // only Supplier Management reactivates
        call("PUT", "/api/suppliers/" + id + "/status", shini.adminToken(), "{\"active\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        product(shini.adminToken(), "POST", "/api/products", "Labneh", "33333333", "Dairy Co")
                .andExpect(status().isCreated());
    }

    @Test
    void theProductScreenLinksToALegacySupplierWhoseNameDiffersOnlyBySpaces() throws Exception {
        db.update("INSERT INTO suppliers (store_id, name) VALUES (?, 'Al  Quds')", shini.storeId());
        long legacy = db.queryForObject("SELECT supplier_id FROM suppliers WHERE name = 'Al  Quds'", Long.class);
        product(shini.adminToken(), "POST", "/api/products", "Milk", "11111111", "Al Quds")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.supplierId").value(legacy));
        product(shini.adminToken(), "POST", "/api/products", "Laban", "22222222", "  al   QUDS ")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.supplierId").value(legacy));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM suppliers WHERE store_id = ?", Long.class, shini.storeId()))
                .isEqualTo(1);
        assertThat(db.queryForObject("SELECT name FROM suppliers WHERE supplier_id = ?", String.class, legacy))
                .as("legacy name not rewritten").isEqualTo("Al  Quds");
        // a brand new name is stored normalized
        product(shini.adminToken(), "POST", "/api/products", "Juice", "33333333", "  Fresh    Farms ")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.supplierName").value("Fresh Farms"));
    }

    @Test
    void anAmbiguousLegacyNameIsRefusedInsteadOfGuessing() throws Exception {
        db.update("INSERT INTO suppliers (store_id, name) VALUES (?, 'X  Y'), (?, 'X   Y')", shini.storeId(), shini.storeId());
        product(shini.adminToken(), "POST", "/api/products", "Milk", "11111111", "X Y")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUPPLIER_NAME_AMBIGUOUS"))
                .andExpect(jsonPath("$.fieldErrors.supplierName").exists());
        // the exact stored name still works exactly as before
        long exact = db.queryForObject("SELECT supplier_id FROM suppliers WHERE name = 'X   Y'", Long.class);
        product(shini.adminToken(), "POST", "/api/products", "Milk", "11111111", "X   Y")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.supplierId").value(exact));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM products", Long.class)).isEqualTo(1);
    }

    @Test
    void theSameSupplierNameInTwoStoresStaysTwoSeparateSuppliers() throws Exception {
        product(shini.adminToken(), "POST", "/api/products", "Milk", "11111111", "Dairy Co").andExpect(status().isCreated());
        product(neshan.adminToken(), "POST", "/api/products", "Milk", "11111111", "Dairy Co").andExpect(status().isCreated());
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT supplier_id) FROM products", Long.class)).isEqualTo(2);
        long shiniSupplier = db.queryForObject("SELECT supplier_id FROM suppliers WHERE store_id = ?", Long.class,
                shini.storeId());
        call("PUT", "/api/suppliers/" + shiniSupplier + "/status", shini.adminToken(), "{\"active\":false}")
                .andExpect(status().isOk());
        // Neshan is not affected by Shini deactivating its own supplier
        product(neshan.adminToken(), "POST", "/api/products", "Laban", "22222222", "Dairy Co").andExpect(status().isCreated());
    }
}
