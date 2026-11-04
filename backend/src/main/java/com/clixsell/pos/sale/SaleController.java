package com.clixsell.pos.sale;

import com.clixsell.pos.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sales API used by the Cashier Dashboard (Cashier and Store Admin; own store only).
 *
 * <pre>
 * POST   /api/sales                        complete the current invoice   → INSERT sale, items, payment, receipt; UPDATE stock
 * POST   /api/sales/suspended              suspend a new invoice          → INSERT sale (SUSPENDED) + items
 * PUT    /api/sales/suspended/{id}         suspend a resumed invoice again→ UPDATE
 * GET    /api/sales/suspended              suspended invoices of the store
 * DELETE /api/sales/suspended/{id}         cancel a suspended invoice     → UPDATE status = CANCELED
 * GET    /api/sales/{id}                   one invoice (e.g. to resume it)
 * GET    /api/sales/lookup?code=…          completed sale by invoice number or receipt QR (returns)
 * </pre>
 */
@RestController
@RequestMapping("/api/sales")
public class SaleController {

    private final SaleService service;

    public SaleController(SaleService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SaleResponse complete(@AuthenticationPrincipal CurrentUser user, @RequestBody SaleRequest request) {
        return service.complete(user, request);
    }

    @PostMapping("/suspended")
    @ResponseStatus(HttpStatus.CREATED)
    public SaleResponse suspend(@AuthenticationPrincipal CurrentUser user, @RequestBody SaleRequest request) {
        return service.suspend(user, withoutId(request));
    }

    @PutMapping("/suspended/{id}")
    public SaleResponse suspendAgain(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                     @RequestBody SaleRequest request) {
        return service.suspend(user, new SaleRequest(id, request.items(), request.discountType(),
                request.discountValue(), null));
    }

    @GetMapping("/suspended")
    public List<SaleResponse> suspended(@AuthenticationPrincipal CurrentUser user) {
        return service.listSuspended(user.requireStoreId());
    }

    @DeleteMapping("/suspended/{id}")
    public SaleResponse cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return service.cancelSuspended(user.requireStoreId(), id);
    }

    @GetMapping("/lookup")
    public SaleResponse lookup(@AuthenticationPrincipal CurrentUser user, @RequestParam String code) {
        return service.lookup(user.requireStoreId(), code);
    }

    @GetMapping("/{id}")
    public SaleResponse get(@AuthenticationPrincipal CurrentUser user, @PathVariable long id) {
        return service.get(user.requireStoreId(), id);
    }

    private static SaleRequest withoutId(SaleRequest r) {
        return new SaleRequest(null, r.items(), r.discountType(), r.discountValue(), null);
    }
}
