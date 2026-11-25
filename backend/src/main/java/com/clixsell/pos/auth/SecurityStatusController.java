package com.clixsell.pos.auth;

import com.clixsell.pos.user.User;
import com.clixsell.pos.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * GET /api/admin/security-status     the signed-in System Admin's own account (System Admin only)
 * GET /api/auth/me/security-status   the signed-in admin's own account (System Admin, Store Admin)
 * </pre>
 * {@code {"demoPasswordInUse": true}} when this administrator still signs in with a publicly
 * documented demo password, so Settings can show a warning. Only a yes / no about the caller's
 * OWN account: no password, hash, username or other account is returned. Read only: nothing is
 * changed. Always false in the demo profile, where the demo password is intended.
 * Access: {@code /api/admin/**} (System Admin) and {@code /api/auth/me/security-status} (System
 * Admin, Store Admin — the same roles that may change their own password); Cashiers get 403.
 */
@RestController
public class SecurityStatusController {

    private final UserRepository users;
    private final DemoPasswords demoPasswords;

    public SecurityStatusController(UserRepository users, DemoPasswords demoPasswords) {
        this.users = users;
        this.demoPasswords = demoPasswords;
    }

    public record SecurityStatus(boolean demoPasswordInUse) {
    }

    @GetMapping({"/api/admin/security-status", "/api/auth/me/security-status"})
    public SecurityStatus status(@AuthenticationPrincipal CurrentUser user) {
        if (demoPasswords.allowed()) return new SecurityStatus(false);
        String hash = users.findById(user.userId()).map(User::getPasswordHash).orElse(null);
        return new SecurityStatus(demoPasswords.hashUsesDemoPassword(hash));
    }
}
