package com.clixsell.pos.auth;

import com.clixsell.pos.auth.LoginFailedException.Reason;
import com.clixsell.pos.user.User;
import com.clixsell.pos.user.UserRepository;
import com.clixsell.pos.user.UserResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Login, logout, and recognising the user behind a login token.
 *
 * <ul>
 *   <li>Passwords are checked against their BCrypt hash.</li>
 *   <li>Unknown username and wrong password give the same answer.</li>
 *   <li>5 wrong passwords in a row lock the account for 5 minutes.</li>
 *   <li>Each login gets a random 256-bit token; only its SHA-256 hash is stored.</li>
 * </ul>
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final Duration sessionLength;
    private final SecureRandom random = new SecureRandom();
    /** Checked when the username does not exist, so both cases take the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, UserSessionRepository sessions, PasswordEncoder passwordEncoder,
                       @Value("${clixsell.auth.session-hours:12}") long sessionHours) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.sessionLength = Duration.ofHours(sessionHours);
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    /**
     * Checks the username and password and starts a session.
     *
     * <p>The failed-attempt counter must be saved even though the login fails,
     * so a failed login does not roll back the transaction.
     */
    @Transactional(noRollbackFor = LoginFailedException.class)
    public LoginResponse login(LoginRequest request) {
        String username = request == null ? "" : User.normalizeUsername(request.username());
        String password = request == null || request.password() == null ? "" : request.password();
        LocalDateTime now = LocalDateTime.now();

        User user = users.findByUsernameIgnoreCase(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new LoginFailedException(Reason.INVALID_CREDENTIALS);
        }
        if (user.isLocked(now)) {
            throw new LoginFailedException(Reason.ACCOUNT_LOCKED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(now);
            users.save(user);
            throw new LoginFailedException(user.isLocked(now) ? Reason.ACCOUNT_LOCKED : Reason.INVALID_CREDENTIALS);
        }
        if (!user.canWork()) {
            throw new LoginFailedException(Reason.ACCOUNT_DISABLED);
        }

        user.recordSuccessfulLogin();
        users.save(user);
        sessions.deleteByExpiresAtBefore(now);

        String token = newToken();
        LocalDateTime createdAt = now.withNano(0);
        LocalDateTime expiresAt = createdAt.plus(sessionLength);
        sessions.save(new UserSession(user, hash(token), createdAt, expiresAt));
        return new LoginResponse(token, expiresAt, UserResponse.from(user));
    }

    /** Ends the session of this token. */
    @Transactional
    public void logout(String token) {
        sessions.findByTokenHash(hash(token)).ifPresent(sessions::delete);
    }

    /**
     * Returns the user of a valid token: the session exists and has not
     * expired, and the user and their store are still active.
     */
    @Transactional(readOnly = true)
    public Optional<CurrentUser> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return sessions.findByTokenHash(hash(token))
                .filter(s -> !s.isExpired(LocalDateTime.now()))
                .map(UserSession::getUser)
                .filter(User::canWork)
                .map(CurrentUser::of);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
