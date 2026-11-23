package com.clixsell.pos;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Helpers for the Guardian Mode API tests: real products, sales, suspensions and cancellations through the API. */
abstract class GuardianApiTestBase extends ApiTestBase {

    /** A cashier with its own full name (to tell users apart in the log); returns [userId, token]. */
    protected Object[] namedCashier(String adminToken, String username, String fullName) throws Exception {
        MvcResult r = mvc.perform(post("/api/store/cashiers").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"fullName\":\"%s\",\"password\":\"Cashier-Pass-9\"}"
                                .formatted(username, fullName)))
                .andExpect(status().isCreated()).andReturn();
        return new Object[]{jsonLong(r, "userId"), login(username, "Cashier-Pass-9")};
    }

    protected long product(String adminToken, String name, String barcode, String category, double price,
                           Double cost, int quantity, int minStock) throws Exception {
        MvcResult r = mvc.perform(post("/api/products").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","barcode":"%s","category":"%s","sellingPrice":%s,"quantity":%d,
                                 "minStock":%d,"supplierName":"Main Supplier","costPrice":%s}
                                """.formatted(name, barcode, category, price, quantity, minStock, cost)))
                .andExpect(status().isCreated()).andReturn();
        return jsonLong(r, "productId");
    }

    protected static String items(Map<Long, Integer> lines) {
        StringBuilder b = new StringBuilder("[");
        lines.forEach((id, q) -> b.append(b.length() > 1 ? "," : "")
                .append("{\"productId\":").append(id).append(",\"quantity\":").append(q).append('}'));
        return b.append(']').toString();
    }

    /** Completes a sale; discount e.g. "PERCENT", 10 or null. Returns the sale id. */
    protected long sell(String token, Map<Long, Integer> lines, String discountType, Double discount) throws Exception {
        return jsonLong(completeSale(token, null, lines, discountType, discount).andExpect(status().isCreated())
                .andReturn(), "saleId");
    }

    protected ResultActions completeSale(String token, Long saleId, Map<Long, Integer> lines, String discountType,
                                         Double discount) throws Exception {
        return mvc.perform(post("/api/sales").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"saleId\":%s,\"items\":%s,\"discountType\":%s,\"discountValue\":%s,\"paymentMethod\":\"CASH\"}"
                        .formatted(saleId, items(lines), discountType == null ? null : "\"" + discountType + "\"",
                                discount)));
    }

    protected long suspend(String token, Map<Long, Integer> lines, String discountType, Double discount) throws Exception {
        MvcResult r = mvc.perform(post("/api/sales/suspended").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":%s,\"discountType\":%s,\"discountValue\":%s}".formatted(items(lines),
                                discountType == null ? null : "\"" + discountType + "\"", discount)))
                .andExpect(status().isCreated()).andReturn();
        return jsonLong(r, "saleId");
    }

    protected void cancel(String token, long saleId) throws Exception {
        mvc.perform(delete("/api/sales/suspended/" + saleId).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    protected void deleteProduct(String adminToken, long productId) throws Exception {
        mvc.perform(delete("/api/products/" + productId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isNoContent());
    }

    protected void returnOne(String token, long saleId, long productId) throws Exception {
        long item = db.queryForObject("SELECT sale_item_id FROM sale_items WHERE sale_id = ? AND product_id = ?",
                Long.class, saleId, productId);
        mvc.perform(post("/api/returns").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"saleId\":%d,\"saleItemId\":%d,\"quantity\":1,\"reason\":\"Damaged\"}"
                                .formatted(saleId, item)))
                .andExpect(status().isCreated());
    }

    protected void setGuardian(String adminToken, boolean on) throws Exception {
        mvc.perform(put("/api/guardian/status").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":" + on + "}"))
                .andExpect(status().isOk());
    }

    protected String getJson(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    protected static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    protected static List<Map<String, Object>> alerts(String json) {
        return JsonPath.read(json, "$.alerts");
    }

    protected static Map<String, Object> alert(String json, String code) {
        List<Map<String, Object>> found = JsonPath.read(json, "$.alerts[?(@.code == '" + code + "')]");
        if (found.isEmpty()) throw new AssertionError("No alert " + code + " in " + json);
        return found.get(0);
    }
}
