package com.clixsell.pos.user;

import com.clixsell.pos.auth.DemoPasswords;
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
    private final DemoPasswords demoPasswords;

    public StaffService(UserRepository users, StoreRepository stores, UserSessionRepository sessions,
                        PasswordEncoder passwordEncoder, DemoPasswords demoPasswords) {
        this.users = users;
        this.demoPasswords = demoPasswords;
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
        // No staff account (Store Admin or Cashier) may get a published demo password outside the
        // demo profile. Checked before anything is saved.
        demoPasswords.rejectIfDemo("password", password);
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

    /**
     * Sets a new password, unlocks the account and ends its current logins. The new password of a
     * staff account (Store Admin or Cashier) may not be a published demo password outside the demo
     * profile. A refused password changes nothing: no hash, no unlock, no session ended.
     */
    public UserResponse resetPassword(long storeId, Role role, long userId, String newPassword) {
        User.validatePassword(newPassword);
        User user = find(storeId, role, userId); // another store's account stays "not found" (404)
        demoPasswords.rejectIfDemo("password", newPassword);
        user.changePassword(passwordEncoder.encode(newPassword));
        sessions.deleteByUser_UserId(userId);
        return UserResponse.from(users.save(user));
    }

    /**
     * The Cashiers of THIS store that still sign in with a published demo password (read only;
     * nothing is changed). Only ids are returned — never a password or a hash. Always empty in the
     * demo profile, where the demo passwords are intended. Uses the normal BCrypt check of the stored
     * hash, cached per hash by {@link DemoPasswords}; the accounts are checked in parallel because
     * the first check of an account costs about 8 BCrypt comparisons.
     */
    @Transactional(readOnly = true)
    public List<Long> cashiersWithDemoPassword(long storeId) {
        findStore(storeId);
        if (demoPasswords.allowed()) return List.of();
        List<User> cashiers = users.findAllByStore_StoreIdAndRoleOrderByUserIdAsc(storeId, Role.CASHIER);
        return cashiers.parallelStream()
                .filter(u -> demoPasswords.hashUsesDemoPassword(u.getPasswordHash()))
                .map(User::getUserId)
                .sorted()
                .toList();
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
