package com.clixsell.pos.auth;

import com.clixsell.pos.user.UserRepository;
import com.clixsell.pos.user.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /api/auth/login    username + password → token and user (anyone)
 * POST /api/auth/logout   ends this login          (logged in)
 * GET  /api/auth/me       the logged-in user       (logged in)
 * PUT  /api/auth/me/password  own password      (System Admin, Store Admin; not Cashier)
 * </pre>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final UserRepository users;

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        return auth.login(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String token = TokenAuthenticationFilter.bearerToken(request);
        if (token != null) auth.logout(token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal CurrentUser user) {
        return users.findById(user.userId()).map(UserResponse::from).orElseThrow();
    }

    /**
     * Changes the logged-in admin's own password (System Admin, Store Admin;
     * Cashiers get 403). Answers 204; the passwords are never returned.
     */
    @PutMapping("/me/password")
    public ResponseEntity<Void> changeOwnPassword(@AuthenticationPrincipal CurrentUser user,
                                                  @RequestBody ChangePasswordRequest body,
                                                  HttpServletRequest request) {
        auth.changeOwnPassword(user, TokenAuthenticationFilter.bearerToken(request), body);
        return ResponseEntity.noContent().build();
    }
}
