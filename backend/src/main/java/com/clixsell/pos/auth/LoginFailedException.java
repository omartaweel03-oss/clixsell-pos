package com.clixsell.pos.auth;

/**
 * A login attempt was refused. Returned as 401 (wrong username or password,
 * account disabled) or 429 (account temporarily locked).
 */
public class LoginFailedException extends RuntimeException {

    public enum Reason {
        /** Same answer for unknown username and wrong password, on purpose. */
        INVALID_CREDENTIALS("Invalid username or password"),
        ACCOUNT_DISABLED("This account or its store has been deactivated"),
        ACCOUNT_LOCKED("Too many wrong attempts. Try again in a few minutes");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    public LoginFailedException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
