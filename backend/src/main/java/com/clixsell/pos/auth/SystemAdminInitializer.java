package com.clixsell.pos.auth;

import com.clixsell.pos.common.ValidationException;
import com.clixsell.pos.user.Role;
import com.clixsell.pos.user.User;
import com.clixsell.pos.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


/**
 * Creates the first System Admin when the backend starts and NO System Admin exists yet.
 *
 * <ul>
 *   <li>The password comes only from explicit configuration: the environment variable
 *       {@code CLIXSELL_ADMIN_PASSWORD} (or {@code clixsell.system-admin.password}). There is no
 *       built-in default password.</li>
 *   <li>If a System Admin must be created and no password is configured, the start stops with a
 *       clear message. No password is ever generated, printed or written to a log.</li>
 *   <li>The publicly documented demo passwords are refused, except with
 *       {@code clixsell.system-admin.allow-demo-password=true} (set only by the {@code demo}
 *       profile, for presentations and tests on throwaway databases).</li>
 *   <li>When a System Admin exists, nothing is created, changed or reset, so existing
 *       installations keep working without any configuration. Only a read-only check runs: a
 *       System Admin that still uses a published demo password gets a clear WARN line in the log
 *       (the username, never the password or its hash), and the same warning is shown to that
 *       System Admin in Settings ({@code GET /api/admin/security-status}).</li>
 *   <li>The same credential rules are checked by {@code DatabaseLocationGuard} BEFORE a new
 *       database file is created ({@link #initialCredentialsProblem}).</li>
 * </ul>
 */
@Component
public class SystemAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemAdminInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final DemoPasswords demoPasswords;
    private final String username;
    private final String password;

    public SystemAdminInitializer(UserRepository users, PasswordEncoder passwordEncoder, DemoPasswords demoPasswords,
                                  @Value("${clixsell.system-admin.username:sysadmin}") String username,
                                  @Value("${clixsell.system-admin.password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demoPasswords = demoPasswords;
        this.username = username;
        this.password = password;
    }

    /**
     * Why the first System Admin cannot be created with these settings, or null when it can.
     * Never contains the password. Used here and by the database guard before a new file exists.
     */
    public static String initialCredentialsProblem(String username, String password, boolean allowDemoPassword) {
        if (password == null || password.isBlank()) {
            return """
                    No System Admin exists yet and no initial password is configured.
                    Set the environment variable CLIXSELL_ADMIN_PASSWORD (at least 8 characters, letters and
                    digits) for this first start, then start the backend again. The password is not shown
                    or logged anywhere; change it later in Settings -> Profile.""";
        }
        if (!allowDemoPassword && DemoPasswords.isPublic(password)) {
            return """
                    The configured initial System Admin password is a publicly documented demo password.
                    Choose your own password in CLIXSELL_ADMIN_PASSWORD (demo passwords are allowed only
                    with the 'demo' profile on a throwaway database).""";
        }
        try {
            User.validateNewAccount(username, "System Administrator", password);
        } catch (ValidationException e) {
            return "The initial System Admin settings are not valid: " + e.getFieldErrors()
                    + ". Check clixsell.system-admin.username and CLIXSELL_ADMIN_PASSWORD.";
        }
        return null;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.SYSTEM_ADMIN)) {
            warnAboutDemoPasswords(); // read only: never reset or touch an existing account
            return;
        }
        String problem = initialCredentialsProblem(username, password, demoPasswords.allowed());
        if (problem != null) throw new IllegalStateException(problem);
        users.save(User.create(Role.SYSTEM_ADMIN, null, username, "System Administrator",
                passwordEncoder.encode(password)));
        log.info("First System Admin '{}' created with the configured password", username);
    }

    private void warnAboutDemoPasswords() {
        for (User admin : users.findAllByRole(Role.SYSTEM_ADMIN)) {
            if (!demoPasswords.hashUsesDemoPassword(admin.getPasswordHash())) continue;
            if (demoPasswords.allowed()) {
                log.info("Demo profile: System Admin '{}' uses the published demo password (allowed only in the"
                        + " demo profile, on a throwaway database).", admin.getUsername());
            } else {
                log.warn("SECURITY WARNING: System Admin '{}' still uses a publicly documented demo password."
                        + " Anyone who knows the demo password can sign in as System Admin. Sign in and change it now:"
                        + " Settings -> Profile -> Change password. ClixSell did not change it automatically.",
                        admin.getUsername());
            }
        }
    }
}
