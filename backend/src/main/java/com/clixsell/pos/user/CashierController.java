package com.clixsell.pos.user;

import com.clixsell.pos.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A Store Admin manages the cashiers of THEIR OWN store (STORE_ADMIN only).
 * The store comes from the logged-in Store Admin, never from the request,
 * so they cannot reach another store's cashiers.
 *
 * <pre>
 * GET  /api/store/cashiers                 list
 * POST /api/store/cashiers                 create (201)
 * PUT  /api/store/cashiers/{id}/status     activate / deactivate
 * PUT  /api/store/cashiers/{id}/password   reset password
 * </pre>
 */
@RestController
@RequestMapping("/api/store/cashiers")
public class CashierController {

    private final StaffService staff;

    public CashierController(StaffService staff) {
        this.staff = staff;
    }

    @GetMapping
    public List<UserResponse> list(@AuthenticationPrincipal CurrentUser admin) {
        return staff.list(admin.requireStoreId(), Role.CASHIER);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@AuthenticationPrincipal CurrentUser admin, @RequestBody NewUserRequest request) {
        return staff.create(admin.requireStoreId(), Role.CASHIER, request);
    }

    @PutMapping("/{id}/status")
    public UserResponse setStatus(@AuthenticationPrincipal CurrentUser admin, @PathVariable long id,
                                  @RequestBody StatusRequest request) {
        return staff.setStatus(admin.requireStoreId(), Role.CASHIER, id, request.status());
    }

    @PutMapping("/{id}/password")
    public UserResponse resetPassword(@AuthenticationPrincipal CurrentUser admin, @PathVariable long id,
                                      @RequestBody PasswordResetRequest request) {
        return staff.resetPassword(admin.requireStoreId(), Role.CASHIER, id, request.password());
    }
}
