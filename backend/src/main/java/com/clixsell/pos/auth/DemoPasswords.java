package com.clixsell.pos.auth;

import com.clixsell.pos.common.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The publicly documented demo passwords (README, demo data, guides) and what ClixSell does with
 * them outside the {@code demo} profile:
 * <ul>
 *   <li>refuses them as a NEW administrator password ({@link #rejectIfDemo});</li>
 *   <li>recognises an EXISTING account that still uses one ({@link #hashUsesDemoPassword}), with the
 *       normal password check of the stored hash (BCrypt) — nothing is decrypted, changed or reset.</li>
 * </ul>
 * No password and no hash is ever logged or returned. {@code clixsell.system-admin.allow-demo-password=true}
 * (set only by the demo profile, for presentations and tests on throwaway databases) allows them.
 */
@Component
public class DemoPasswords {

    /** Refused as a new administrator password, compared ignoring letter case. */
    public static final Set<String> PUBLIC = Set.of("admin12345", "admin-pass-1", "admin-pass-2", "cashier-pass-1");

    /** The exact spellings that were published; checked against a stored hash. */
    static final List<String> PUBLISHED_SPELLINGS = List.of(
            "Admin12345", "admin12345", "Admin-Pass-1", "admin-pass-1", "Admin-Pass-2", "admin-pass-2",
            "Cashier-Pass-1", "cashier-pass-1");

    /** Validation message (the Flutter app shows its own Arabic / English text for it). */
    public static final String REJECTED_MESSAGE =
            "This is a publicly documented demo password. Choose your own password.";

    private static final int CACHE_LIMIT = 64;

    private final PasswordEncoder encoder;
    private final boolean allowed;
    /** stored hash -> uses a published password; a changed password has a new hash, so no stale answer. */
    private final Map<String, Boolean> checked = new ConcurrentHashMap<>();

    public DemoPasswords(PasswordEncoder encoder,
                         @Value("${clixsell.system-admin.allow-demo-password:false}") boolean allowed) {
        this.encoder = encoder;
        this.allowed = allowed;
    }

    /** True only in the demo profile. */
    public boolean allowed() {
        return allowed;
    }

    /** Is this (new) password one of the published demo passwords, in any letter case? */
    public static boolean isPublic(String password) {
        return password != null && PUBLIC.contains(password.toLowerCase(Locale.ROOT));
    }

    /** Outside the demo profile: a published demo password is refused for this field (400). */
    public void rejectIfDemo(String field, String newPassword) {
        if (!allowed && isPublic(newPassword)) {
            throw new ValidationException(Map.of(field, REJECTED_MESSAGE));
        }
    }

    /** Does this stored password hash belong to a published demo password? (read only, cached per hash) */
    public boolean hashUsesDemoPassword(String passwordHash) {
        if (passwordHash == null || passwordHash.isEmpty()) return false;
        Boolean known = checked.get(passwordHash);
        if (known != null) return known;
        boolean demo = false;
        for (String candidate : PUBLISHED_SPELLINGS) {
            try {
                if (encoder.matches(candidate, passwordHash)) {
                    demo = true;
                    break;
                }
            } catch (RuntimeException e) {
                return false; // not a hash this encoder understands: nothing to report
            }
        }
        if (checked.size() >= CACHE_LIMIT) checked.clear();
        checked.put(passwordHash, demo);
        return demo;
    }
}
