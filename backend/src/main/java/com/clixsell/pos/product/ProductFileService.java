package com.clixsell.pos.product;

import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.store.StoreRepository;
import com.clixsell.pos.supplier.Supplier;
import com.clixsell.pos.supplier.SupplierNames;
import com.clixsell.pos.supplier.SupplierService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Product import / export as CSV files (UR-M11, SR24, Figure 3.3), Store Admin of the store only.
 *
 * <ul>
 *   <li><b>Export</b>: every product of the logged-in user's store, the same columns the import
 *       reads (see {@link ProductCsv}).</li>
 *   <li><b>Preview</b>: reads and checks the whole file with the same rules as the product form
 *       ({@link Product#validate}, unique barcode in the store and in the file, supplier found or
 *       created by name exactly as the form does, an inactive supplier refused) and writes
 *       NOTHING. Returns every row with its errors, the suppliers that would be created, and the
 *       SHA-256 of the file.</li>
 *   <li><b>Import</b>: needs the same file, its preview hash and an explicit confirmation. It
 *       checks everything again inside ONE transaction and creates all products or none.
 *       Only NEW products are created: an existing barcode is an error, nothing is ever updated
 *       or overwritten (an update / upsert mode would be a separate decision).</li>
 * </ul>
 * Limits: {@link #MAX_BYTES} bytes and {@link #MAX_ROWS} products per file. No new table, no
 * migration. Products are created through {@link ProductService#createProduct}, so every rule
 * of the product form applies unchanged.
 */
@Service
public class ProductFileService {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 5000;

    private final ProductService productService;
    private final ProductRepository products;
    private final SupplierService suppliers;
    private final StoreRepository stores;

    public ProductFileService(ProductService productService, ProductRepository products, SupplierService suppliers,
                              StoreRepository stores) {
        this.productService = productService;
        this.products = products;
        this.suppliers = suppliers;
        this.stores = stores;
    }

    // ── export ──────────────────────────────────────────────────────────────────────

    public record ExportFile(String fileName, byte[] bytes, int count) {
    }

    @Transactional(readOnly = true)
    public ExportFile export(long storeId) {
        List<Product> list = new ArrayList<>(products.findAllByStore_StoreIdOrderByProductIdDesc(storeId));
        java.util.Collections.reverse(list); // oldest first, like the order they were added
        return new ExportFile("clixsell-products-" + LocalDate.now() + ".csv", ProductCsv.write(list), list.size());
    }

    // ── preview / import ────────────────────────────────────────────────────────────

    /** One row of the file as read, with its problems (field → message). */
    public record Row(int line, Map<String, String> values, String supplierStatus, Map<String, String> errors) {
    }

    /** What would happen; also the body of a refused import. */
    public record Preview(String fileName, String fileHash, int totalRows, int validRows, int invalidRows,
                          List<String> fileErrors, List<String> newSuppliers, List<Row> rows, boolean canImport,
                          int maxRows, int maxBytes) {
    }

    public record Result(String fileName, int created, List<String> suppliersCreated, List<Long> productIds) {
    }

    /** A refused file: the response carries the full preview so every row error can be shown. */
    public static final class ImportRefusedException extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        private final Object details;

        public ImportRefusedException(HttpStatus status, String code, String message, Object details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = details;
        }

        public HttpStatus getStatus() { return status; }
        public String getCode() { return code; }
        public Object getDetails() { return details; }
    }

    @Transactional(readOnly = true)
    public Preview preview(long storeId, String fileName, byte[] bytes) {
        return check(storeId, fileName, bytes);
    }

    /**
     * Creates every product of the file in ONE transaction, or none. The file must be exactly the
     * previewed one ({@code expectedHash}) and pass every check again now.
     */
    @Transactional
    public Result importFile(long storeId, String fileName, byte[] bytes, String expectedHash) {
        Preview p = check(storeId, fileName, bytes);
        if (expectedHash == null || !expectedHash.equalsIgnoreCase(p.fileHash())) {
            throw new ImportRefusedException(HttpStatus.CONFLICT, "IMPORT_FILE_CHANGED",
                    "This is not the file that was previewed. Preview it again before importing.", p);
        }
        if (!p.canImport()) {
            throw new ImportRefusedException(HttpStatus.valueOf(422), "IMPORT_INVALID",
                    "The file has errors; nothing was imported.", p);
        }
        List<Long> ids = new ArrayList<>();
        Map<String, String> createdSuppliers = new LinkedHashMap<>();
        for (Row r : p.rows()) {
            Map<String, String> v = r.values();
            ProductRequest req = request(v);
            boolean isNew = "NEW".equals(r.supplierStatus());
            Product saved = productService.createProduct(storeId, req); // same rules as the form; throws = rollback
            ids.add(saved.getProductId());
            if (isNew) createdSuppliers.putIfAbsent(SupplierNames.key(saved.getSupplier().getName()), saved.getSupplier().getName());
        }
        return new Result(p.fileName(), ids.size(), List.copyOf(createdSuppliers.values()), ids);
    }

    private static ProductRequest request(Map<String, String> v) {
        return new ProductRequest(v.get("name"), v.get("barcode"), v.get("category"), decimal(v.get("sellingPrice")),
                integer(v.get("quantity")), integer(v.get("minStock")), v.get("supplier"), null, null,
                v.get("costPrice") == null || v.get("costPrice").isBlank() ? null : decimal(v.get("costPrice")));
    }

    private Preview check(long storeId, String fileName, byte[] bytes) {
        String name = safeName(fileName);
        String hash = sha256(bytes == null ? new byte[0] : bytes);
        List<String> fileErrors = new ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            fileErrors.add("EMPTY_FILE");
            return refused(name, hash, fileErrors);
        }
        if (bytes.length > MAX_BYTES) {
            fileErrors.add("FILE_TOO_LARGE");
            return refused(name, hash, fileErrors);
        }
        ProductCsv.Parsed parsed = ProductCsv.parse(bytes);
        if (parsed.fileError() != null) {
            fileErrors.add(parsed.fileError());
            return refused(name, hash, fileErrors);
        }
        if (parsed.records().isEmpty()) {
            fileErrors.add("EMPTY_FILE");
            return refused(name, hash, fileErrors);
        }
        // header
        List<String> header = parsed.records().get(0).fields().stream().map(h -> h.strip()).toList();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = canonical(header.get(i));
            if (key == null) fileErrors.add("UNKNOWN_COLUMN:" + header.get(i));
            else if (col.putIfAbsent(key, i) != null) fileErrors.add("DUPLICATE_COLUMN:" + key);
        }
        for (String c : ProductCsv.COLUMNS) {
            if (!ProductCsv.OPTIONAL.contains(c) && !col.containsKey(c)) fileErrors.add("MISSING_COLUMN:" + c);
        }
        if (!fileErrors.isEmpty()) return refused(name, hash, fileErrors);
        List<ProductCsv.Record> data = parsed.records().subList(1, parsed.records().size());
        if (data.isEmpty()) {
            fileErrors.add("NO_PRODUCTS");
            return refused(name, hash, fileErrors);
        }
        if (data.size() > MAX_ROWS) {
            fileErrors.add("TOO_MANY_ROWS");
            return refused(name, hash, fileErrors);
        }

        stores.findById(storeId).orElseThrow(); // the logged-in user's store must exist
        Map<String, Integer> barcodeLine = new HashMap<>();
        Map<String, String> newSuppliers = new LinkedHashMap<>();
        Map<String, Optional<Supplier>> supplierCache = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        int valid = 0;
        for (ProductCsv.Record rec : data) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String c : ProductCsv.COLUMNS) {
                Integer i = col.get(c);
                String raw = i == null || i >= rec.fields().size() ? "" : rec.fields().get(i);
                values.put(c, c.equals("name") || c.equals("supplier") ? ProductCsv.untext(raw).strip() : raw.strip());
            }
            Map<String, String> errors = new LinkedHashMap<>();
            if (rec.error() != null) errors.put("row", rec.error());
            if (rec.fields().size() != header.size()) errors.put("row", "COLUMN_COUNT:" + rec.fields().size() + "/" + header.size());

            Double price = null;
            Double cost = null;
            Integer qty = null;
            Integer min = null;
            if (!values.get("sellingPrice").isEmpty()) {
                price = decimal(values.get("sellingPrice"));
                if (price == null) errors.put("sellingPrice", "NOT_A_NUMBER");
            }
            if (!values.get("costPrice").isEmpty()) {
                cost = decimal(values.get("costPrice"));
                if (cost == null) errors.put("costPrice", "NOT_A_NUMBER");
            }
            if (!values.get("quantity").isEmpty()) {
                qty = integer(values.get("quantity"));
                if (qty == null) errors.put("quantity", "NOT_A_WHOLE_NUMBER");
            }
            if (!values.get("minStock").isEmpty()) {
                min = integer(values.get("minStock"));
                if (min == null) errors.put("minStock", "NOT_A_WHOLE_NUMBER");
            }
            try {
                Product.validate(new ProductRequest(values.get("name"), values.get("barcode"), values.get("category"),
                        price, qty, min, values.get("supplier"), null, null, cost));
            } catch (ProductValidationException e) {
                for (var en : e.getFieldErrors().entrySet()) {
                    String field = en.getKey().equals("supplierName") ? "supplier" : en.getKey();
                    errors.putIfAbsent(field, en.getValue());
                }
            }
            String barcode = values.get("barcode");
            if (!errors.containsKey("barcode") && !barcode.isEmpty()) {
                Integer first = barcodeLine.putIfAbsent(barcode, rec.line());
                if (first != null) errors.put("barcode", "DUPLICATE_IN_FILE:" + first);
                else if (products.existsByStore_StoreIdAndBarcode(storeId, barcode)) errors.put("barcode", "EXISTS_IN_STORE");
            }
            String supplierStatus = null;
            String supplier = values.get("supplier");
            if (!errors.containsKey("supplier") && !supplier.isEmpty()) {
                try {
                    String key = SupplierNames.key(supplier.trim());
                    Optional<Supplier> found = supplierCache.computeIfAbsent(key, k -> suppliers.findExisting(storeId, supplier));
                    if (found.isEmpty()) {
                        supplierStatus = "NEW";
                        newSuppliers.putIfAbsent(key, SupplierNames.normalize(supplier.trim()));
                    } else if (!found.get().isActive()) {
                        supplierStatus = "INACTIVE";
                        errors.put("supplier", "SUPPLIER_INACTIVE");
                    } else {
                        supplierStatus = "EXISTING";
                    }
                } catch (ConflictException e) {
                    errors.put("supplier", "SUPPLIER_NAME_AMBIGUOUS");
                }
            }
            if (errors.isEmpty()) valid++;
            rows.add(new Row(rec.line(), values, supplierStatus, errors));
        }
        boolean ok = valid == rows.size();
        return new Preview(name, hash, rows.size(), valid, rows.size() - valid, List.of(),
                List.copyOf(newSuppliers.values()), rows, ok, MAX_ROWS, MAX_BYTES);
    }

    private static Preview refused(String name, String hash, List<String> fileErrors) {
        return new Preview(name, hash, 0, 0, 0, List.copyOf(fileErrors), List.of(), List.of(), false, MAX_ROWS, MAX_BYTES);
    }

    /** Header name → column key (case-insensitive; "supplierName" accepted for "supplier"). */
    private static String canonical(String h) {
        String k = h.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
        for (String c : ProductCsv.COLUMNS) {
            if (c.toLowerCase(Locale.ROOT).equals(k)) return c;
        }
        return k.equals("suppliername") ? "supplier" : null;
    }

    /** Plain decimal (digits, optional single dot): 12, 12.5, 0.75. No exponent, thousands separator or comma. */
    static Double decimal(String s) {
        if (s == null || !s.matches("-?\\d{1,9}(\\.\\d+)?")) return null; // a sign is checked by the product rules
        return Double.valueOf(s);
    }

    static Integer integer(String s) {
        if (s == null || !s.matches("-?\\d{1,9}")) return null;
        return Integer.valueOf(s);
    }

    private static String safeName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "products.csv";
        String n = fileName.replaceAll("[\\p{Cntrl}]", "").strip();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Header-only template for an empty store (same columns, UTF-8 with BOM). */
    public static byte[] template() {
        return ProductCsv.write(List.of());
    }

}
