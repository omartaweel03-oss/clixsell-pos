package com.clixsell.pos.auth;

import com.clixsell.pos.user.UserResponse;

import java.time.LocalDateTime;

/**
 * Result of a successful login. The app sends {@code token} in the header
 * {@code Authorization: Bearer <token>} with every later request.
 */
public record LoginResponse(String token, LocalDateTime expiresAt, UserResponse user) {
}
