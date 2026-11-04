package com.clixsell.pos.store;

import com.clixsell.pos.auth.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/store – the logged-in Store Admin's or Cashier's own store (read only).
 * Only the System Admin can change store details.
 */
@RestController
public class MyStoreController {

    private final StoreService service;

    public MyStoreController(StoreService service) {
        this.service = service;
    }

    @GetMapping("/api/store")
    public StoreResponse myStore(@AuthenticationPrincipal CurrentUser user) {
        return service.getStore(user.requireStoreId());
    }
}
