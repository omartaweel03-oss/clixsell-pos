package com.clixsell.pos.returns;

import com.clixsell.pos.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/returns – return product units of a completed sale
 * → INSERT returns; UPDATE products.quantity (+) and sale_items.returned_quantity.
 * Cashier and Store Admin, own store only.
 */
@RestController
public class ReturnController {

    private final ReturnService service;

    public ReturnController(ReturnService service) {
        this.service = service;
    }

    @PostMapping("/api/returns")
    @ResponseStatus(HttpStatus.CREATED)
    public ReturnResponse create(@AuthenticationPrincipal CurrentUser user, @RequestBody ReturnRequest request) {
        return service.processReturn(user, request);
    }
}
