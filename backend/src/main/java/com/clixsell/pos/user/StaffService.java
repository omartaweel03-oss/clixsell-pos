package com.clixsell.pos.user;

import com.clixsell.pos.auth.UserSessionRepository;
import com.clixsell.pos.common.AccountStatus;
import com.clixsell.pos.common.ConflictException;
import com.clixsell.pos.common.NotFoundException;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.store.Store;
import com.clixsell.pos.store.StoreRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Accounts of a store's staff: Store Admins (managed by the System Admin)
 * and Cashiers (managed by the store's own Store Admin).
 *
 * <p>Every method is given ONE store and ONE role and can only see or change
 * users of that store with that role. A user of another store, or with
 * another role, is reported as "not found".
 */
@Service
@Transactional
public class StaffService {

    private final UserRepository users;
    private final StoreRepository stores;
    private final UserSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;

    public StaffService(UserRepository users, StoreRepository stores, UserSessionRepository sessions,
                        PasswordEncoder passwordEncoder) {
        this.users = users;
        this.stores = stores;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list(long storeId, Role role) {
        findStore(storeId);
        return users.findAllByStore_StoreIdAndRoleOrderByUserIdAsc(storeId, role).stream()
                .map(UserResponse::from).toList();
    }

    public UserResponse create(long storeId, Role role, NewUserRequest request) {
        if (role == Role.SYSTEM_ADMIN) throw new IllegalArgumentException("Store staff only");
        Store store = findStore(storeId);
        String username = request == null ? null : request.username();
        String fullName = request == null ? null : request.fullName();
        String password = request == null ? null : request.password();
        User.validateNewAccount(username, fullName, password);
        if (users.existsByUsernameIgnoreCase(User.normalizeUsername(username))) {
            throw new ConflictException("DUPLICATE_USERNAME", "username", "This username is already taken");
        }
        User user = User.create(role, store, username, fullName, passwordEncoder.encode(password));
        return UserResponse.from(users.save(user));
    }

    /** Deactivating also logs the user out at once. */
    public UserResponse setStatus(long storeId, Role role, long userId, AccountStatus status) {
        if (status == null) {
            throw new ValidationException(Map.of("status", "Status must be ACTIVE or INACTIVE"));
        }
        User user = find(storeId, role, userId);
        user.setStatus(status);
        if (status == AccountStatus.INACTIVE) {
            sessions.deleteByUser_UserId(userId);
        }
        return UserResponse.from(users.save(user));
    }

    /** Sets a new password, unlocks the account and ends its current logins. */
    public UserResponse resetPassword(long storeId, Role role, long userId, String newPassword) {
        User.validatePassword(newPassword);
        User user = find(storeId, role, userId);
        user.changePassword(passwordEncoder.encode(newPassword));
        sessions.deleteByUser_UserId(userId);
        return UserResponse.from(users.save(user));
    }

    private User find(long storeId, Role role, long userId) {
        return users.findByUserIdAndStore_StoreIdAndRole(userId, storeId, role)
                .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User " + userId + " was not found"));
    }

    private Store findStore(long storeId) {
        return stores.findById(storeId)
                .orElseThrow(() -> new NotFoundException("STORE_NOT_FOUND", "Store " + storeId + " was not found"));
    }
}
