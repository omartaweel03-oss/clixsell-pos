package com.clixsell.pos.user;

import org.springframework.http.HttpStatus;
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
 * The System Admin manages each store's Store Admins (SYSTEM_ADMIN only).
 *
 * <pre>
 * GET  /api/admin/stores/{storeId}/admins                    list
 * POST /api/admin/stores/{storeId}/admins                    create (201)
 * PUT  /api/admin/stores/{storeId}/admins/{userId}/status    activate / deactivate
 * PUT  /api/admin/stores/{storeId}/admins/{userId}/password  reset password
 * </pre>
 */
@RestController
@RequestMapping("/api/admin/stores/{storeId}/admins")
public class StoreAdminController {

    private final StaffService staff;

    public StoreAdminController(StaffService staff) {
        this.staff = staff;
    }

    @GetMapping
    public List<UserResponse> list(@PathVariable long storeId) {
        return staff.list(storeId, Role.STORE_ADMIN);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@PathVariable long storeId, @RequestBody NewUserRequest request) {
        return staff.create(storeId, Role.STORE_ADMIN, request);
    }

    @PutMapping("/{userId}/status")
    public UserResponse setStatus(@PathVariable long storeId, @PathVariable long userId,
                                  @RequestBody StatusRequest request) {
        return staff.setStatus(storeId, Role.STORE_ADMIN, userId, request.status());
    }

    @PutMapping("/{userId}/password")
    public UserResponse resetPassword(@PathVariable long storeId, @PathVariable long userId,
                                      @RequestBody PasswordResetRequest request) {
        return staff.resetPassword(storeId, Role.STORE_ADMIN, userId, request.password());
    }
}
