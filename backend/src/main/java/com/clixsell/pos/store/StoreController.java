package com.clixsell.pos.store;

import com.clixsell.pos.user.StatusRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Store management for the System Admin only (see SecurityConfig).
 *
 * <pre>
 * GET  /api/admin/stores               all stores
 * POST /api/admin/stores               create a store          (201)
 * GET  /api/admin/stores/{id}          one store
 * PUT  /api/admin/stores/{id}          edit name, address, phone
 * PUT  /api/admin/stores/{id}/status   activate / deactivate
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/stores")
public class StoreController {

    private final StoreService service;

    public StoreController(StoreService service) {
        this.service = service;
    }

    @GetMapping
    public List<StoreResponse> list() {
        return service.listStores();
    }

    @PostMapping
    public ResponseEntity<StoreResponse> create(@RequestBody StoreRequest request) {
        StoreResponse saved = service.createStore(request);
        return ResponseEntity.created(URI.create("/api/admin/stores/" + saved.storeId())).body(saved);
    }

    @GetMapping("/{id}")
    public StoreResponse get(@PathVariable long id) {
        return service.getStore(id);
    }

    @PutMapping("/{id}")
    public StoreResponse update(@PathVariable long id, @RequestBody StoreRequest request) {
        return service.updateStore(id, request);
    }

    @PutMapping("/{id}/status")
    public StoreResponse setStatus(@PathVariable long id, @RequestBody StatusRequest request) {
        return service.setStatus(id, request.status());
    }
}
