package com.clixsell.pos.auth;

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

import java.util.Locale;
import java.util.Set;

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
 *   <li>When a System Admin exists, nothing happens: no password is reset or checked, so
 *       existing installations keep working without any configuration.</li>
 * </ul>
 */
@Component
public class SystemAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemAdminInitializer.class);

    /** Passwords published in the README / demo data: never accepted for a real installation. */
    static final Set<String> PUBLIC_DEMO_PASSWORDS = Set.of("admin12345", "admin-pass-1", "admin-pass-2", "cashier-pass-1");

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final boolean allowDemoPassword;

    public SystemAdminInitializer(UserRepository users, PasswordEncoder passwordEncoder,
                                  @Value("${clixsell.system-admin.username:sysadmin}") String username,
                                  @Value("${clixsell.system-admin.password:}") String password,
                                  @Value("${clixsell.system-admin.allow-demo-password:false}") boolean allowDemoPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.allowDemoPassword = allowDemoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.SYSTEM_ADMIN)) {
            return; // never reset or touch an existing account
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("""
                    No System Admin exists yet and no initial password is configured.
                    Set the environment variable CLIXSELL_ADMIN_PASSWORD (at least 8 characters, letters and
                    digits) for this first start, then start the backend again. The password is not shown
                    or logged anywhere; change it later in Settings -> Profile.""");
        }
        if (!allowDemoPassword && PUBLIC_DEMO_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("""
                    The configured initial System Admin password is a publicly documented demo password.
                    Choose your own password in CLIXSELL_ADMIN_PASSWORD (demo passwords are allowed only
                    with the 'demo' profile on a throwaway database).""");
        }
        User.validateNewAccount(username, "System Administrator", password);
        users.save(User.create(Role.SYSTEM_ADMIN, null, username, "System Administrator",
                passwordEncoder.encode(password)));
        log.info("First System Admin '{}' created with the configured password", username);
    }
}
