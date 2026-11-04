package com.clixsell.pos.user;

import com.clixsell.pos.common.AccountStatus;

import java.time.LocalDateTime;

/** User data returned by the API. Never contains the password hash. */
public record UserResponse(
        Long userId,
        String username,
        String fullName,
        Role role,
        Long storeId,
        String storeName,
        AccountStatus status,
        LocalDateTime createdAt
) {
    public static UserResponse from(User u) {
        return new UserResponse(
                u.getUserId(),
                u.getUsername(),
                u.getFullName(),
                u.getRole(),
                u.getStore() == null ? null : u.getStore().getStoreId(),
                u.getStore() == null ? null : u.getStore().getName(),
                u.getStatus(),
                u.getCreatedAt());
    }
}
