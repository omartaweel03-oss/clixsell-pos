package com.clixsell.pos.product;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.StrictJson;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.user.Role;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Product files (UR-M11 / SR24, Figure 3.3 "Import" / "Export") — Store Admin of the store only
 * (SecurityConfig, and checked again here). The store always comes from the login.
 *
 * <pre>
 * GET  /api/product-files/export          the store's products as CSV (UTF-8, attachment)
 * POST /api/product-files/import/preview  {fileName, content (base64)} → every row checked, nothing saved
 * POST /api/product-files/import          {fileName, content, previewHash, confirmImport: true}
 *                                         → all products created in one transaction, or none
 * </pre>
 */
@RestController
@RequestMapping("/api/product-files")
public class ProductFileController {

    private static final Set<String> PREVIEW_FIELDS = Set.of("fileName", "content");
    private static final Set<String> IMPORT_FIELDS = Set.of("fileName", "content", "previewHash", "confirmImport");
    /** Base64 of the largest file accepted (plus a little room). */
    private static final int MAX_BASE64 = (ProductFileService.MAX_BYTES / 3 + 2) * 4 + 16;

    private final ProductFileService files;

    public ProductFileController(ProductFileService files) {
        this.files = files;
    }

    private static long storeOf(CurrentUser user) {
        if (user.role() != Role.STORE_ADMIN) throw new AccessDeniedException("Store Admin only");
        return user.requireStoreId();
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal CurrentUser user) {
        ProductFileService.ExportFile f = files.export(storeOf(user));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Product-Count", Integer.toString(f.count()))
                .cacheControl(CacheControl.noStore())
                .body(f.bytes());
    }

    @PostMapping("/import/preview")
    public ProductFileService.Preview preview(@AuthenticationPrincipal CurrentUser user,
                                              @RequestBody Map<String, Object> body) {
        long store = storeOf(user);
        StrictJson.requireOnly(body, PREVIEW_FIELDS);
        return files.preview(store, StrictJson.text(body, "fileName"), decode(body));
    }

    @PostMapping("/import")
    public ResponseEntity<ProductFileService.Result> importFile(@AuthenticationPrincipal CurrentUser user,
                                                                @RequestBody Map<String, Object> body) {
        long store = storeOf(user);
        StrictJson.requireOnly(body, IMPORT_FIELDS);
        if (!StrictJson.requiredBoolean(body, "confirmImport")) {
            throw new ValidationException(Map.of("confirmImport",
                    "Confirm that the previewed products should be created (confirmImport: true)"));
        }
        String hash = StrictJson.text(body, "previewHash");
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
            throw new ValidationException(Map.of("previewHash", "The previewHash of the checked file is required"));
        }
        byte[] content = decode(body);
        try {
            return ResponseEntity.status(201).body(files.importFile(store, StrictJson.text(body, "fileName"), content, hash));
        } catch (ProductFileService.ImportRefusedException | ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            // The import transaction was rolled back before this point: nothing of the file was saved.
            throw new ProductFileService.ImportRefusedException(org.springframework.http.HttpStatus.CONFLICT, "IMPORT_FAILED",
                    "The import could not be completed and nothing was saved. Check the file and try again.", null);
        }
    }

    private static byte[] decode(Map<String, Object> body) {
        Object c = body.get("content");
        if (!(c instanceof String s)) {
            throw new ValidationException(Map.of("content", "The file content (base64) is required"));
        }
        if (s.length() > MAX_BASE64) {
            throw new ValidationException(Map.of("content", "The file is larger than "
                    + ProductFileService.MAX_BYTES / (1024 * 1024) + " MB"));
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(Map.of("content", "The file content is not valid base64"));
        }
    }
}
