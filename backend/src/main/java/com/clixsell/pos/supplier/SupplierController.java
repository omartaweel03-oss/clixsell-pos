package com.clixsell.pos.supplier;

import com.clixsell.pos.auth.CurrentUser;
import com.clixsell.pos.common.StrictJson;
import com.clixsell.pos.user.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import java.util.Set;

/**
 * Supplier Management (Purchase Orders, Phase 1) — Store Admin of the store only
 * (SecurityConfig, and checked again here).
 *
 * <pre>
 * GET  /api/suppliers              all suppliers of the store (active and inactive) + product counts
 * GET  /api/suppliers/{id}         one supplier with its products
 * POST /api/suppliers              {name, phone, whatsappNumber, address}          → 201
 * PUT  /api/suppliers/{id}         {name, phone, whatsappNumber, address}
 * PUT  /api/suppliers/{id}/status  {active: true | false}
 * </pre>
 * Bodies are strict (D25): any other field, e.g. storeId, is refused with 400 UNKNOWN_FIELD.
 * There is no DELETE: suppliers are deactivated, never deleted.
 */
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private static final Set<String> DETAIL_FIELDS = Set.of("name", "phone", "whatsappNumber", "address");
    private static final Set<String> STATUS_FIELDS = Set.of("active");

    private final SupplierService service;

    public SupplierController(SupplierService service) {
        this.service = service;
    }

    private static long storeOf(CurrentUser user) {
        if (user.role() != Role.STORE_ADMIN) throw new AccessDeniedException("Store Admin only");
        return user.requireStoreId();
    }

    private static SupplierService.SupplierInput input(Map<String, Object> body) {
        StrictJson.requireOnly(body, DETAIL_FIELDS);
        return new SupplierService.SupplierInput(StrictJson.text(body, "name"), StrictJson.text(body, "phone"),
                StrictJson.text(body, "whatsappNumber"), StrictJson.text(body, "address"));
    }

    @GetMapping
    public List<SupplierResponse> list(@AuthenticationPrincipal CurrentUser user) {
        return service.list(storeOf(user)).stream().map(SupplierResponse::summary).toList();
    }

    @GetMapping("/{id}")
    public SupplierResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        long store = storeOf(user);
        return SupplierResponse.details(service.get(store, id), service.productsOf(store, id));
    }

    @PostMapping
    public ResponseEntity<SupplierResponse> create(@AuthenticationPrincipal CurrentUser user,
                                                   @RequestBody Map<String, Object> body) {
        long store = storeOf(user);
        Supplier s = service.create(store, input(body));
        return ResponseEntity.created(URI.create("/api/suppliers/" + s.getSupplierId()))
                .body(SupplierResponse.details(s, List.of()));
    }

    @PutMapping("/{id}")
    public SupplierResponse update(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                   @RequestBody Map<String, Object> body) {
        long store = storeOf(user);
        Supplier s = service.update(store, id, input(body));
        return SupplierResponse.details(s, service.productsOf(store, id));
    }

    @PutMapping("/{id}/status")
    public SupplierResponse setStatus(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                      @RequestBody Map<String, Object> body) {
        long store = storeOf(user);
        StrictJson.requireOnly(body, STATUS_FIELDS);
        Supplier s = service.setActive(store, id, StrictJson.requiredBoolean(body, "active"));
        return SupplierResponse.details(s, service.productsOf(store, id));
    }
}
