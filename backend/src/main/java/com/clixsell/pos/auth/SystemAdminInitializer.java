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

import java.security.SecureRandom;

/**
 * Creates the first System Admin when the backend starts and none exists.
 *
 * <p>Username and password come from application.properties
 * (clixsell.system-admin.username / .password, or the environment variable
 * CLIXSELL_ADMIN_PASSWORD). If no password is set, a random one is generated
 * and printed once in the console. Nothing happens on later starts.
 */
@Component
public class SystemAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemAdminInitializer.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public SystemAdminInitializer(UserRepository users, PasswordEncoder passwordEncoder,
                                  @Value("${clixsell.system-admin.username:sysadmin}") String username,
                                  @Value("${clixsell.system-admin.password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.SYSTEM_ADMIN)) {
            return;
        }
        boolean generated = password == null || password.isBlank();
        String initialPassword = generated ? randomPassword() : password;
        User.validateNewAccount(username, "System Administrator", initialPassword);

        users.save(User.create(Role.SYSTEM_ADMIN, null, username, "System Administrator",
                passwordEncoder.encode(initialPassword)));

        if (generated) {
            log.warn("""

                    ============================================================
                     First System Admin created
                       username: {}
                       password: {}
                     Write this password down: it is shown only once.
                    ============================================================""", username, initialPassword);
        } else {
            log.info("First System Admin '{}' created with the configured password", username);
        }
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
