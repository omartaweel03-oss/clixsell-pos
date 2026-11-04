package com.clixsell.pos.product;

import com.clixsell.pos.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Product REST API used by the Flutter Product Screen.
 *
 * <pre>
 * GET    /api/products                    list all products       → SELECT
 * GET    /api/products/{id}               one product             → SELECT
 * POST   /api/products                    insert a product        → INSERT  (201)
 * PUT    /api/products/{id}               update a product        → UPDATE  (200)
 * DELETE /api/products/{id}               delete a product        → DELETE  (204)
 * GET    /api/products/generate-barcode   a new unique barcode
 * </pre>
 *
 * Only logged-in Store Admins (all operations) and Cashiers (read only) may
 * call it; see SecurityConfig. Every operation works on the logged-in user's
 * own store: the store is never taken from the request.
 *
 * Errors are returned as JSON by {@link com.clixsell.pos.common.ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductResponse> list(@AuthenticationPrincipal CurrentUser user) {
        return service.listProducts(user.requireStoreId()).stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ProductResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return ProductResponse.from(service.getProduct(user.requireStoreId(), id));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@AuthenticationPrincipal CurrentUser user,
                                                  @RequestBody ProductRequest request) {
        Product saved = service.createProduct(user.requireStoreId(), request);
        return ResponseEntity
                .created(URI.create("/api/products/" + saved.getProductId()))
                .body(ProductResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                  @RequestBody ProductRequest request) {
        return ProductResponse.from(service.updateProduct(user.requireStoreId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        service.deleteProduct(user.requireStoreId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/generate-barcode")
    public Map<String, String> generateBarcode(@AuthenticationPrincipal CurrentUser user) {
        return Map.of("barcode", service.generateUniqueBarcode(user.requireStoreId()));
    }
}
