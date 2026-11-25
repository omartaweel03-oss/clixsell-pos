package com.clixsell.pos.product;

import com.clixsell.pos.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Product import / export (UR-M11, SR24) through the real API and database: export of the own
 * store only (UTF-8 + BOM, quoting, formula guard, cost for the Store Admin), preview that writes
 * nothing with row errors, all-or-nothing import of NEW products only, file limits, double
 * submission, an interrupted import rolled back, other-store isolation, roles, and an
 * export → re-import round trip into a clean store. The persisted rows are checked every time.
 */
class ProductFileApiTest extends ApiTestBase {

    private static final String HEADER = "name,barcode,category,sellingPrice,costPrice,quantity,minStock,supplier\n";

    private static <T> T read(MvcResult r, String path) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    private static int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private MvcResult preview(String token, String fileName, byte[] bytes) throws Exception {
        String body = "{\"fileName\":\"" + fileName + "\",\"content\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}";
        var req = post("/api/product-files/import/preview").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", bearer(token));
        return mvc.perform(req).andReturn();
    }

    private MvcResult doImport(String token, String fileName, byte[] bytes, String hash, boolean confirm) throws Exception {
        String body = "{\"fileName\":\"" + fileName + "\",\"content\":\"" + Base64.getEncoder().encodeToString(bytes)
                + "\",\"previewHash\":" + (hash == null ? "null" : "\"" + hash + "\"") + ",\"confirmImport\":" + confirm + "}";
        var req = post("/api/product-files/import").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req = req.header("Authorization", bearer(token));
        return mvc.perform(req).andReturn();
    }

    /** Preview, then import exactly that file with its hash. */
    private MvcResult previewAndImport(String token, byte[] bytes) throws Exception {
        MvcResult p = preview(token, "products.csv", bytes);
        assertThat(status(p)).isEqualTo(200);
        return doImport(token, "products.csv", bytes, read(p, "$.fileHash"), true);
    }

    private MvcResult export(String token) throws Exception {
        var req = get("/api/product-files/export");
        if (token != null) req = req.header("Authorization", bearer(token));
        return mvc.perform(req).andReturn();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private long products(long storeId) {
        return db.queryForObject("SELECT COUNT(*) FROM products WHERE store_id = ?", Long.class, storeId);
    }

    private long suppliers(long storeId) {
        return db.queryForObject("SELECT COUNT(*) FROM suppliers WHERE store_id = ?", Long.class, storeId);
    }

    private Map<String, Object> row(long storeId, String barcode) {
        return db.queryForMap("""
                SELECT p.name, p.category, p.selling_price, p.cost_price, p.quantity, p.min_stock, s.name AS supplier,
                       s.store_id AS supplier_store
                FROM products p JOIN suppliers s ON s.supplier_id = p.supplier_id
                WHERE p.store_id = ? AND p.barcode = ?""", storeId, barcode);
    }

    private void product(String token, String name, String barcode, String supplier, String extra) throws Exception {
        String json = productJson(name, barcode, 4.5, 10, 3, supplier);
        if (extra != null) json = json.replace("\"supplierName\"", extra + ",\"supplierName\"");
        MvcResult r = mvc.perform(post("/api/products").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
    }

    // ── export ──────────────────────────────────────────────────────────────────────

    @Test
    void exportIsTheOwnStoreOnlyAsUtf8CsvWithCostQuotingAndTheFormulaGuard() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        StoreSetup neshan = setupStore("Neshan", "neshan");
        product(shini.adminToken(), "لبنة 500غ", "11111111", "مزارع الخليل", "\"costPrice\":3.25");
        product(shini.adminToken(), "Milk, 1L \\\"Fresh\\\"", "22222222", "Dairy Co", null);
        product(shini.adminToken(), "=HYPERLINK(1)", "33333333", "Dairy Co", "\"costPrice\":1");
        product(neshan.adminToken(), "Neshan Secret", "44444444", "Other Co", null);

        MvcResult r = export(shini.adminToken());
        assertThat(status(r)).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("text/csv").contains("UTF-8");
        assertThat(r.getResponse().getHeader("Content-Disposition")).startsWith("attachment").contains(".csv");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        byte[] bytes = r.getResponse().getContentAsByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(csv).startsWith(HEADER.replace("\n", "\r\n"));
        assertThat(csv).contains("لبنة 500غ,11111111,dairy,4.5,3.25,10,3,مزارع الخليل\r\n");
        assertThat(csv).contains("\"Milk, 1L \"\"Fresh\"\"\",22222222,dairy,4.5,,10,3,Dairy Co\r\n");
        assertThat(csv).contains("'=HYPERLINK(1),33333333,dairy,4.5,1,10,3,Dairy Co\r\n");
        assertThat(csv).doesNotContain("Neshan Secret").doesNotContain("44444444");
        assertThat(csv.split("\r\n")).hasSize(4);
        assertThat(r.getResponse().getHeader("X-Product-Count")).isEqualTo("3");

        // roles: Store Admin only (cost price is a manager field)
        assertThat(status(export(shini.cashierToken()))).isEqualTo(403);
        assertThat(status(export(sysadminToken()))).isEqualTo(403);
        assertThat(status(export(null))).isEqualTo(401);
        assertThat(status(preview(shini.cashierToken(), "x.csv", utf8(HEADER)))).isEqualTo(403);
        assertThat(status(doImport(shini.cashierToken(), "x.csv", utf8(HEADER), "a".repeat(64), true))).isEqualTo(403);
    }

    @Test
    void exportThenReimportIntoACleanStoreGivesTheSameProducts() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        product(shini.adminToken(), "لبنة 500غ", "11111111", "مزارع الخليل", "\"costPrice\":3.25");
        product(shini.adminToken(), "Milk, 1L \\\"Fresh\\\"", "22222222", "Dairy Co", null);
        product(shini.adminToken(), "=HYPERLINK(1)", "33333333", "Dairy Co", "\"costPrice\":1");
        byte[] file = export(shini.adminToken()).getResponse().getContentAsByteArray();

        StoreSetup clean = setupStore("Clean", "clean");
        MvcResult p = preview(clean.adminToken(), "export.csv", file);
        assertThat((Boolean) read(p, "$.canImport")).isTrue();
        assertThat((Integer) read(p, "$.validRows")).isEqualTo(3);
        assertThat((List<String>) read(p, "$.newSuppliers")).containsExactlyInAnyOrder("مزارع الخليل", "Dairy Co");
        assertThat(products(clean.storeId())).as("preview writes nothing").isZero();
        assertThat(suppliers(clean.storeId())).isZero();

        MvcResult imp = doImport(clean.adminToken(), "export.csv", file, read(p, "$.fileHash"), true);
        assertThat(status(imp)).as(imp.getResponse().getContentAsString()).isEqualTo(201);
        assertThat((Integer) read(imp, "$.created")).isEqualTo(3);
        for (String bc : List.of("11111111", "22222222", "33333333")) {
            Map<String, Object> a = row(shini.storeId(), bc);
            Map<String, Object> b = row(clean.storeId(), bc);
            a.remove("supplier_store");
            b.remove("supplier_store");
            assertThat(b).as(bc).isEqualTo(a);
        }
        assertThat(row(clean.storeId(), "33333333").get("name")).as("formula guard removed again").isEqualTo("=HYPERLINK(1)");
        assertThat(row(clean.storeId(), "22222222").get("cost_price")).as("unknown cost stays unknown").isNull();
        assertThat(((Number) row(clean.storeId(), "11111111").get("supplier_store")).longValue()).isEqualTo(clean.storeId());
        assertThat(products(shini.storeId())).isEqualTo(3);
    }

    // ── preview and row errors ──────────────────────────────────────────────────────

    @Test
    void everyRowProblemIsReportedAndTheWholeFileIsRefusedWithoutWriting() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        product(shini.adminToken(), "Existing", "99999999", "Dairy Co", null);
        mvc.perform(post("/api/products").header("Authorization", bearer(shini.adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content(productJson("Sleepy", "88888888", 2, 1, 1, "Sleepy Supplier")));
        long sleepy = db.queryForObject("SELECT supplier_id FROM suppliers WHERE name = 'Sleepy Supplier'", Long.class);
        assertThat(status(mvc.perform(put("/api/suppliers/" + sleepy + "/status").header("Authorization", bearer(shini.adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andReturn())).isEqualTo(200);
        long before = products(shini.storeId());
        long suppliersBefore = suppliers(shini.storeId());

        String csv = HEADER
                + "Good Arabic لبن,10000001,dairy,4,2,5,1,New Farm\n"           // 2 ok
                + "Good English,10000002,bakery,1.5,,0,0,Dairy Co\n"            // 3 ok (no cost, zero stock)
                + "Dup in file,10000001,dairy,4,2,5,1,Dairy Co\n"               // 4 duplicate barcode in file
                + "Exists,99999999,dairy,4,2,5,1,Dairy Co\n"                    // 5 exists in store
                + ",10000005,dairy,4,2,5,1,Dairy Co\n"                          // 6 missing name
                + "Bad price,10000006,dairy,abc,2,5,1,Dairy Co\n"               // 7 not a number
                + "Zero price,10000007,dairy,0,2,5,1,Dairy Co\n"                // 8 price must be > 0
                + "Three decimals,10000008,dairy,1.234,2,5,1,Dairy Co\n"       // 9 decimals
                + "Negative stock,10000009,dairy,4,2,-3,1,Dairy Co\n"          // 10 negative quantity
                + "Half unit,10000010,dairy,4,2,1.5,1,Dairy Co\n"              // 11 not whole
                + "Bad category,10000011,toys,4,2,5,1,Dairy Co\n"              // 12 unknown category
                + "Inactive,10000012,dairy,4,2,5,1,Sleepy Supplier\n"          // 13 inactive supplier
                + "Short barcode,123,dairy,4,2,5,1,Dairy Co\n"                 // 14 barcode length
                + "Too,many,columns,4,2,5,1,Dairy Co,extra\n"                  // 15 column count
                + "Neg cost,10000015,dairy,4,-1,5,1,Dairy Co\n";               // 16 negative cost
        MvcResult p = preview(shini.adminToken(), "bad.csv", utf8(csv));
        assertThat(status(p)).isEqualTo(200);
        assertThat((Boolean) read(p, "$.canImport")).isFalse();
        assertThat((Integer) read(p, "$.totalRows")).isEqualTo(15);
        assertThat((Integer) read(p, "$.validRows")).isEqualTo(2);
        assertThat((Integer) read(p, "$.invalidRows")).isEqualTo(13);
        Map<Integer, Map<String, String>> errors = new java.util.HashMap<>();
        List<Map<String, Object>> rows = read(p, "$.rows");
        for (Map<String, Object> r : rows) errors.put((Integer) r.get("line"), (Map<String, String>) r.get("errors"));
        assertThat(errors.get(2)).isEmpty();
        assertThat(errors.get(3)).isEmpty();
        assertThat(errors.get(4)).containsEntry("barcode", "DUPLICATE_IN_FILE:2");
        assertThat(errors.get(5)).containsEntry("barcode", "EXISTS_IN_STORE");
        assertThat(errors.get(6)).containsKey("name");
        assertThat(errors.get(7)).containsEntry("sellingPrice", "NOT_A_NUMBER");
        assertThat(errors.get(8)).containsKey("sellingPrice");
        assertThat(errors.get(9).get("sellingPrice")).contains("2 decimal");
        assertThat(errors.get(10).get("quantity")).contains("negative");
        assertThat(errors.get(11)).containsEntry("quantity", "NOT_A_WHOLE_NUMBER");
        assertThat(errors.get(12).get("category")).contains("Unknown category");
        assertThat(errors.get(13)).containsEntry("supplier", "SUPPLIER_INACTIVE");
        assertThat(errors.get(14)).containsKey("barcode");
        assertThat(errors.get(15).get("row")).startsWith("COLUMN_COUNT");
        assertThat(errors.get(16).get("costPrice")).contains("negative");
        assertThat((List<String>) read(p, "$.newSuppliers")).containsExactly("New Farm");

        MvcResult imp = doImport(shini.adminToken(), "bad.csv", utf8(csv), read(p, "$.fileHash"), true);
        assertThat(status(imp)).isEqualTo(422);
        assertThat((String) read(imp, "$.code")).isEqualTo("IMPORT_INVALID");
        assertThat((Integer) read(imp, "$.details.invalidRows")).isEqualTo(13);
        assertThat(products(shini.storeId())).as("nothing created, not even the 2 good rows").isEqualTo(before);
        assertThat(suppliers(shini.storeId())).as("the new supplier was not created").isEqualTo(suppliersBefore);
    }

    @Test
    void fileLevelProblemsAreRefusedClearly() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("", "EMPTY_FILE");
        cases.put("\n\n", "EMPTY_FILE");
        cases.put(HEADER, "NO_PRODUCTS");
        cases.put("name,barcode,category,sellingPrice,quantity,minStock\nA,12345678,dairy,1,1,1\n", "MISSING_COLUMN:supplier");
        cases.put(HEADER.replace("\n", ",colour\n") + "A,12345678,dairy,1,,1,1,S,red\n", "UNKNOWN_COLUMN:colour");
        cases.put("name,name,barcode,category,sellingPrice,quantity,minStock,supplier\n", "DUPLICATE_COLUMN:name");
        cases.put(HEADER + "\"Unclosed,12345678,dairy,1,,1,1,S\n", "UNTERMINATED_QUOTE:2");
        for (var c : cases.entrySet()) {
            MvcResult p = preview(shini.adminToken(), "f.csv", utf8(c.getKey()));
            assertThat(status(p)).isEqualTo(200);
            assertThat((Boolean) read(p, "$.canImport")).isFalse();
            assertThat((List<String>) read(p, "$.fileErrors")).as(c.getValue()).contains(c.getValue());
        }
        byte[] cp1256 = (HEADER + "لبنة,12345678,dairy,1,,1,1,مورد\n").getBytes(Charset.forName("windows-1256"));
        assertThat((List<String>) read(preview(shini.adminToken(), "ansi.csv", cp1256), "$.fileErrors")).containsExactly("NOT_UTF8");
        // oversized: refused before parsing (by size), nothing created
        byte[] big = new byte[ProductFileService.MAX_BYTES + 10];
        java.util.Arrays.fill(big, (byte) 'a');
        MvcResult tooBig = preview(shini.adminToken(), "big.csv", big);
        assertThat(status(tooBig) == 400 || ((List<String>) read(tooBig, "$.fileErrors")).contains("FILE_TOO_LARGE")).isTrue();
        // too many rows
        StringBuilder many = new StringBuilder(HEADER);
        for (int i = 0; i <= ProductFileService.MAX_ROWS; i++) many.append("P").append(i).append(',').append(20000000 + i).append(",dairy,1,,1,1,S\n");
        assertThat((List<String>) read(preview(shini.adminToken(), "many.csv", utf8(many.toString())), "$.fileErrors"))
                .containsExactly("TOO_MANY_ROWS");
        // bad base64, missing confirmation, missing hash
        MvcResult badB64 = mvc.perform(post("/api/product-files/import/preview").header("Authorization", bearer(shini.adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fileName\":\"x.csv\",\"content\":\"%%%\"}")).andReturn();
        assertThat(status(badB64)).isEqualTo(400);
        byte[] ok = utf8(HEADER + "A product,12345678,dairy,1,,1,1,Some Supplier\n");
        String hash = read(preview(shini.adminToken(), "ok.csv", ok), "$.fileHash");
        assertThat(status(doImport(shini.adminToken(), "ok.csv", ok, hash, false))).isEqualTo(400);
        assertThat(status(doImport(shini.adminToken(), "ok.csv", ok, null, true))).isEqualTo(400);
        assertThat(products(shini.storeId())).isZero();
    }

    // ── import ──────────────────────────────────────────────────────────────────────

    @Test
    void aValidMixedArabicEnglishFileWithBomIsImportedCompletelyAndOnlyNewProductsAreCreated() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        product(shini.adminToken(), "Old Milk", "55555555", "Dairy Co", null);
        Map<String, Object> oldRow = row(shini.storeId(), "55555555");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        String csv = HEADER.replace("supplier", "Supplier")
                + "خبز طابون,60000001,bakery,5,3.5,29,10,مخبز رام الله\r\n"
                + "\"Cheese, \"\"Nabulsi\"\"\",60000002,dairy,18,12.25,11,5,dairy co\r\n"   // existing supplier, other case
                + "\"Two\nline name\",60000003,other,1,,0,0,New Supplier\r\n";
        byte[] file = new byte[bom.length + utf8(csv).length];
        System.arraycopy(bom, 0, file, 0, 3);
        System.arraycopy(utf8(csv), 0, file, 3, utf8(csv).length);
        long suppliersBefore = suppliers(shini.storeId());

        MvcResult imp = previewAndImport(shini.adminToken(), file);
        assertThat(status(imp)).as(imp.getResponse().getContentAsString()).isEqualTo(201);
        assertThat((Integer) read(imp, "$.created")).isEqualTo(3);
        assertThat((List<String>) read(imp, "$.suppliersCreated")).containsExactlyInAnyOrder("مخبز رام الله", "New Supplier");
        assertThat(suppliers(shini.storeId())).isEqualTo(suppliersBefore + 2);
        assertThat(row(shini.storeId(), "60000001")).containsEntry("name", "خبز طابون").containsEntry("quantity", 29)
                .containsEntry("selling_price", 5.0).containsEntry("cost_price", 3.5).containsEntry("supplier", "مخبز رام الله");
        assertThat(row(shini.storeId(), "60000002")).containsEntry("name", "Cheese, \"Nabulsi\"").containsEntry("supplier", "Dairy Co");
        assertThat(row(shini.storeId(), "60000003")).containsEntry("name", "Two\nline name").containsEntry("cost_price", null);
        assertThat(row(shini.storeId(), "55555555")).as("existing product untouched").isEqualTo(oldRow);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM activity_log WHERE event_type = 'STOCK_ADJUSTED'", Long.class))
                .as("creating products is not a stock adjustment").isZero();

        // the same file again: every barcode exists now -> refused whole, nothing duplicated or changed
        long count = products(shini.storeId());
        MvcResult again = previewAndImport(shini.adminToken(), file);
        assertThat(status(again)).isEqualTo(422);
        assertThat((List<String>) read(again, "$.details.rows[*].errors.barcode")).containsOnly("EXISTS_IN_STORE");
        assertThat(products(shini.storeId())).isEqualTo(count);
        assertThat(row(shini.storeId(), "60000001").get("quantity")).as("stock not increased by a re-import").isEqualTo(29);
    }

    @Test
    void theImportedFileMustBeThePreviewedOne() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        byte[] a = utf8(HEADER + "A product,12345678,dairy,1,,1,1,Some Supplier\n");
        byte[] b = utf8(HEADER + "A product,12345678,dairy,100,,1,1,Some Supplier\n");
        String hashA = read(preview(shini.adminToken(), "a.csv", a), "$.fileHash");
        MvcResult r = doImport(shini.adminToken(), "a.csv", b, hashA, true);
        assertThat(status(r)).isEqualTo(409);
        assertThat((String) read(r, "$.code")).isEqualTo("IMPORT_FILE_CHANGED");
        assertThat(products(shini.storeId())).isZero();
    }

    @Test
    void twoImportsOfTheSameFileAtOnceCreateTheProductsOnce() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 20; i++) csv.append("Item ").append(i).append(',').append(70000000 + i).append(",snacks,2,1,10,2,Snack Co\n");
        byte[] file = utf8(csv.toString());
        String hash = read(preview(shini.adminToken(), "s.csv", file), "$.fileHash");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return status(doImport(shini.adminToken(), "s.csv", file, hash, true));
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : fs) codes.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(codes).containsOnlyOnce(201);
        assertThat(codes.stream().filter(c -> c != 201)).allMatch(c -> c == 422 || c == 409);
        assertThat(products(shini.storeId())).isEqualTo(20);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM suppliers WHERE store_id = ? AND name = 'Snack Co'", Long.class,
                shini.storeId())).isEqualTo(1);
    }

    @Test
    void anImportInterruptedHalfWayLeavesNothingBehind() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        long productsBefore = products(shini.storeId());
        long suppliersBefore = suppliers(shini.storeId());
        // TEST database only: make the 3rd insert fail inside the transaction
        db.execute("CREATE TRIGGER test_fail_import BEFORE INSERT ON products WHEN NEW.barcode = '80000003' "
                + "BEGIN SELECT RAISE(ABORT, 'simulated failure'); END");
        try {
            byte[] file = utf8(HEADER
                    + "First,80000001,dairy,1,,1,1,Fresh Supplier One\n"
                    + "Second,80000002,dairy,1,,1,1,Fresh Supplier Two\n"
                    + "Third,80000003,dairy,1,,1,1,Fresh Supplier Three\n"
                    + "Fourth,80000004,dairy,1,,1,1,Fresh Supplier One\n");
            MvcResult r = previewAndImport(shini.adminToken(), file);
            assertThat(status(r)).isEqualTo(409);
            assertThat((String) read(r, "$.code")).isEqualTo("IMPORT_FAILED");
        } finally {
            db.execute("DROP TRIGGER test_fail_import");
        }
        assertThat(products(shini.storeId())).as("the first two products were rolled back").isEqualTo(productsBefore);
        assertThat(suppliers(shini.storeId())).as("the new suppliers were rolled back").isEqualTo(suppliersBefore);
    }

    @Test
    void importsStayInTheOwnStore() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        StoreSetup neshan = setupStore("Neshan", "neshan");
        product(shini.adminToken(), "Shini Labneh", "11111111", "Shared Name Co", null);
        Map<String, Object> shiniRow = row(shini.storeId(), "11111111");
        // Neshan imports the same barcode and supplier name: allowed (barcodes are per store), own supplier
        byte[] file = utf8(HEADER + "Neshan Labneh,11111111,dairy,6,,4,2,Shared Name Co\n");
        MvcResult p = preview(neshan.adminToken(), "n.csv", file);
        assertThat((Boolean) read(p, "$.canImport")).isTrue();
        assertThat((List<String>) read(p, "$.newSuppliers")).containsExactly("Shared Name Co");
        assertThat(status(doImport(neshan.adminToken(), "n.csv", file, read(p, "$.fileHash"), true))).isEqualTo(201);
        assertThat(row(neshan.storeId(), "11111111")).containsEntry("name", "Neshan Labneh");
        assertThat(((Number) row(neshan.storeId(), "11111111").get("supplier_store")).longValue()).isEqualTo(neshan.storeId());
        assertThat(row(shini.storeId(), "11111111")).isEqualTo(shiniRow);
        String shiniExport = new String(export(shini.adminToken()).getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(shiniExport).doesNotContain("Neshan Labneh");
    }

    @Test
    void aLargeFileWithinTheLimitIsImportedInOneTransaction() throws Exception {
        StoreSetup shini = setupStore("Shini", "shini");
        StringBuilder csv = new StringBuilder(HEADER);
        int n = 1500;
        for (int i = 0; i < n; i++) {
            csv.append("Bulk ").append(i).append(',').append(30000000 + i).append(i % 2 == 0 ? ",groceries," : ",beverages,")
                    .append(1 + i % 50).append(".25,").append(i % 3 == 0 ? "" : "0.5").append(',').append(i % 100).append(",5,")
                    .append(i % 10 == 0 ? "مورد " + (i % 7) : "Supplier " + (i % 4)).append('\n');
        }
        MvcResult r = previewAndImport(shini.adminToken(), utf8(csv.toString()));
        assertThat(status(r)).as(r.getResponse().getContentAsString()).isEqualTo(201);
        assertThat((Integer) read(r, "$.created")).isEqualTo(n);
        assertThat(products(shini.storeId())).isEqualTo(n);
        assertThat(db.queryForObject("SELECT SUM(quantity) FROM products WHERE store_id = ?", Long.class, shini.storeId()))
                .isEqualTo((long) java.util.stream.IntStream.range(0, n).map(i -> i % 100).sum());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM products WHERE store_id = ? AND cost_price IS NULL", Long.class,
                shini.storeId())).isEqualTo(500);
    }
}
