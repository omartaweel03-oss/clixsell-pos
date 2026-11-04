package com.clixsell.pos.auth;

import com.clixsell.pos.user.Role;
import com.clixsell.pos.user.User;
import org.springframework.security.access.AccessDeniedException;

/**
 * The logged-in user of the current request, built from the login token.
 *
 * <p>Controllers get it with {@code @AuthenticationPrincipal}. The store a user
 * works in comes ONLY from here, never from the request URL or body, which
 * is what keeps each store's data separate.
 */
public record CurrentUser(long userId, String username, String fullName, Role role, Long storeId, String storeName) {

    public static CurrentUser of(User u) {
        return new CurrentUser(
                u.getUserId(),
                u.getUsername(),
                u.getFullName(),
                u.getRole(),
                u.getStore() == null ? null : u.getStore().getStoreId(),
                u.getStore() == null ? null : u.getStore().getName());
    }

    /** The user's store. Only store users have one. */
    public long requireStoreId() {
        if (storeId == null) {
            throw new AccessDeniedException("This account does not belong to a store");
        }
        return storeId;
    }
}
