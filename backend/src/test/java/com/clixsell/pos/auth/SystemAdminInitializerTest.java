package com.clixsell.pos.auth;

import com.clixsell.pos.user.Role;
import com.clixsell.pos.user.User;
import com.clixsell.pos.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A1: the first System Admin comes only from explicit configuration; no built-in or generated
 * password, nothing printed, demo passwords refused outside the demo profile, and an existing
 * System Admin is never touched.
 */
@ExtendWith(OutputCaptureExtension.class)
class SystemAdminInitializerTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    /** A minimal in-memory UserRepository: only the two methods the initializer uses. */
    private static final class Users {
        boolean adminExists;
        final List<User> saved = new ArrayList<>();

        UserRepository proxy() {
            return (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(),
                    new Class<?>[]{UserRepository.class}, (p, m, a) -> switch (m.getName()) {
                        case "existsByRole" -> adminExists || (a[0] == Role.SYSTEM_ADMIN && !saved.isEmpty());
                        case "save" -> {
                            saved.add((User) a[0]);
                            yield a[0];
                        }
                        case "toString" -> "Users";
                        case "hashCode" -> 1;
                        case "equals" -> p == a[0];
                        default -> throw new UnsupportedOperationException(m.getName());
                    });
        }
    }

    private SystemAdminInitializer init(Users u, String password, boolean demo) {
        return new SystemAdminInitializer(u.proxy(), encoder, "sysadmin", password, demo);
    }

    @Test
    void noAdminAndNoPasswordStopsTheStartClearlyAndCreatesNothing(CapturedOutput out) {
        Users u = new Users();
        for (String missing : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> init(u, missing, false).run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CLIXSELL_ADMIN_PASSWORD");
        }
        assertThat(u.saved).isEmpty();
        assertThat(out.getAll()).doesNotContainIgnoringCase("password:");
    }

    @Test
    void theConfiguredPasswordIsStoredOnlyAsAHashAndNeverLogged(CapturedOutput out) {
        Users u = new Users();
        String secret = "Store-Owner-77x";
        init(u, secret, false).run(null);
        assertThat(u.saved).hasSize(1);
        User admin = u.saved.get(0);
        assertThat(admin.getRole()).isEqualTo(Role.SYSTEM_ADMIN);
        assertThat(admin.getPasswordHash()).isNotEqualTo(secret);
        assertThat(encoder.matches(secret, admin.getPasswordHash())).isTrue();
        assertThat(out.getAll()).doesNotContain(secret).contains("created with the configured password");
    }

    @Test
    void anExistingAdminIsNeverResetEvenWithADifferentConfiguredPassword(CapturedOutput out) {
        Users u = new Users();
        u.adminExists = true;
        init(u, "Another-Secret-9", false).run(null);
        init(u, null, false).run(null); // no configuration needed once an admin exists: no lock-out
        assertThat(u.saved).isEmpty();
        assertThat(out.getAll()).doesNotContain("Another-Secret-9");
    }

    @Test
    void publicDemoPasswordsAreRefusedOutsideTheDemoProfile() {
        for (String demo : new String[]{"Admin12345", "admin12345", "Admin-Pass-1", "Cashier-Pass-1"}) {
            Users u = new Users();
            assertThatThrownBy(() -> init(u, demo, false).run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("publicly documented demo password")
                    .hasMessageNotContaining(demo);
            assertThat(u.saved).isEmpty();
        }
        Users demoProfile = new Users();
        init(demoProfile, "Admin12345", true).run(null);
        assertThat(demoProfile.saved).hasSize(1);
    }

    @Test
    void aWeakPasswordIsStillRefusedByTheNormalAccountRules() {
        Users u = new Users();
        assertThatThrownBy(() -> init(u, "short", false).run(null)).isInstanceOf(RuntimeException.class);
        assertThat(u.saved).isEmpty();
    }
}
