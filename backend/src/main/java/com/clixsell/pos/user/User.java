package com.clixsell.pos.user;

import com.clixsell.pos.common.AccountStatus;
import com.clixsell.pos.common.LocalDateTimeTextConverter;
import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.store.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A person who logs in to ClixSell (report: User class, USERS table).
 *
 * <p>Holds the account rules: valid usernames and passwords, which store the
 * user belongs to, and locking the account after too many wrong passwords.
 * The password itself is never stored, only its BCrypt hash.
 */
@Entity
@Table(name = "users")
public class User {

    public static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt only uses the first 72 bytes of a password. */
    public static final int MAX_PASSWORD_BYTES = 72;
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    /** Null only for the System Admin. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime lockedUntil;

    @Column(name = "created_at", nullable = false)
    @Convert(converter = LocalDateTimeTextConverter.class)
    private LocalDateTime createdAt;

    /** Required by JPA. Use {@link #create}. */
    protected User() {
    }

    /**
     * Creates a new, active account. The caller validates the raw password with
     * {@link #validateNewAccount} and passes only its hash.
     */
    public static User create(Role role, Store store, String username, String fullName, String passwordHash) {
        if ((role == Role.SYSTEM_ADMIN) != (store == null)) {
            throw new IllegalArgumentException("A System Admin has no store; every other user needs one");
        }
        User user = new User();
        user.role = role;
        user.store = store;
        user.username = normalizeUsername(username);
        user.fullName = fullName.trim();
        user.passwordHash = passwordHash;
        user.status = AccountStatus.ACTIVE;
        user.createdAt = LocalDateTime.now().withNano(0);
        return user;
    }

    // ── Validation ────────────────────────────────────────────────────────

    /** Checks the fields of a new account. @throws ValidationException listing invalid fields */
    public static void validateNewAccount(String username, String fullName, String password) {
        Map<String, String> errors = new LinkedHashMap<>();
        String u = normalizeUsername(username);
        if (u.isEmpty()) errors.put("username", "Username is required");
        else if (!u.matches("[a-z0-9._-]{3,40}")) {
            errors.put("username", "Username must be 3–40 characters: letters, digits, '.', '_' or '-'");
        }
        String name = fullName == null ? "" : fullName.trim();
        if (name.length() < 2) errors.put("fullName", "Full name must be at least 2 characters");
        else if (name.length() > 80) errors.put("fullName", "Full name must be 80 characters or fewer");
        String passwordError = passwordProblem(password);
        if (passwordError != null) errors.put("password", passwordError);
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    /** @throws ValidationException if the password breaks the password rules */
    public static void validatePassword(String password) {
        String problem = passwordProblem(password);
        if (problem != null) throw new ValidationException(Map.of("password", problem));
    }

    private static String passwordProblem(String password) {
        if (password == null || password.isEmpty()) return "Password is required";
        if (password.length() < MIN_PASSWORD_LENGTH) return "Password must be at least " + MIN_PASSWORD_LENGTH + " characters";
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) return "Password is too long";
        return null;
    }

    /** Usernames are case-insensitive and stored in lower case. */
    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    // ── Login rules ───────────────────────────────────────────────────────

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    /** The user may work only if the account and (for store users) the store are active. */
    public boolean canWork() {
        return isActive() && (store == null || store.isActive());
    }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Counts a wrong password; the 5th in a row locks the account for 5 minutes. */
    public void recordFailedLogin(LocalDateTime now) {
        failedLoginAttempts++;
        if (failedLoginAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION).withNano(0);
            failedLoginAttempts = 0;
        }
    }

    public void recordSuccessfulLogin() {
        failedLoginAttempts = 0;
        lockedUntil = null;
    }

    /** Sets a new password hash and unlocks the account (used by a password reset). */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        recordSuccessfulLogin();
    }

    public void setStatus(AccountStatus newStatus) {
        this.status = newStatus;
    }

    public boolean belongsTo(long storeId) {
        return store != null && store.getStoreId() == storeId;
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public Long getUserId() { return userId; }
    public Store getStore() { return store; }
    public String getUsername() { return username; }
    public String getFullName() { return fullName; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public AccountStatus getStatus() { return status; }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
