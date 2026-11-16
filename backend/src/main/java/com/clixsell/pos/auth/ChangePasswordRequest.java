package com.clixsell.pos.auth;

/** A System Admin or Store Admin changes their own password (Settings → Profile). */
public record ChangePasswordRequest(String currentPassword, String newPassword) {

    /** Never print the passwords (for example in logs). */
    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
