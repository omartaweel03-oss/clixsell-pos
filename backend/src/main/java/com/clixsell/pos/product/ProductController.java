package com.clixsell.pos.product;

import org.springframework.http.ResponseEntity;
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
    public List<ProductResponse> list() {
        return service.listProducts().stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable long id) {
        return ProductResponse.from(service.getProduct(id));
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@RequestBody ProductRequest request) {
        Product saved = service.createProduct(request);
        return ResponseEntity
                .created(URI.create("/api/products/" + saved.getProductId()))
                .body(ProductResponse.from(saved));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable long id, @RequestBody ProductRequest request) {
        return ProductResponse.from(service.updateProduct(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/generate-barcode")
    public Map<String, String> generateBarcode() {
        return Map.of("barcode", service.generateUniqueBarcode());
    }
}
