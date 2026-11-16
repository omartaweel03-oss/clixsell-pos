package com.clixsell.pos.store;

import com.clixsell.pos.auth.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logged-in user's own store. The store is always the one of the
 * session; a store id is never taken from the request.
 * <pre>
 * GET /api/store            Store Admin, Cashier   read the store (POS tax, discount)
 * PUT /api/store/settings   Store Admin            tax rate, maximum discount,
 *                                                   address, phone (Settings, Fig 3.9)
 * </pre>
 * The store name and status stay with the System Admin.
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

    @PutMapping("/api/store/settings")
    public StoreResponse updateSettings(@AuthenticationPrincipal CurrentUser user,
                                        @RequestBody StoreSettingsRequest request) {
        return service.updateMySettings(user.requireStoreId(), request);
    }
}
