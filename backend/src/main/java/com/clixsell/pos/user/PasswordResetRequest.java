package com.clixsell.pos.user;

/** Body of a password reset done by an admin for one of their users. */
public record PasswordResetRequest(String password) {
}
