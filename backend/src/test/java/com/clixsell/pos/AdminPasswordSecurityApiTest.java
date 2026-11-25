package com.clixsell.pos;

import com.clixsell.pos.auth.DemoPasswords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Final security hardening (outside the demo profile):
 * <ol>
 *   <li>a System Admin that still uses a published demo password is warned in Settings
 *       ({@code GET /api/admin/security-status}), can still sign in, and nothing is changed;</li>
 *   <li>a published demo password is refused as a new administrator password (own change, and
 *       the System Admin's reset of a Store Admin), with a clear message; cashiers are unchanged.</li>
 * </ol>
 * The "legacy" System Admin is simulated by writing a demo-password hash into the TEST database
 * only; the original hash is put back after each test.
 */
class AdminPasswordSecurityApiTest extends ApiTestBase {

    private static final String STATUS = "/api/admin/security-status";
    private StoreSetup shini;
    private String sysadminHash;

    @BeforeEach
    void setUp() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        sysadminHash = hash("sysadmin");
    }

    @AfterEach
    void restoreSysadmin() {
        db.update("UPDATE users SET password_hash = ?, failed_login_attempts = 0, locked_until = NULL WHERE username = 'sysadmin'",
                sysadminHash);
    }

    private String hash(String username) {
        return db.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);
    }

    /** TEST database only: makes the System Admin a "legacy" account with the old default password. */
    private void legacySysadmin(String demoPassword) {
        db.update("UPDATE users SET password_hash = ? WHERE username = 'sysadmin'",
                new BCryptPasswordEncoder().encode(demoPassword));
    }

    private ResultActions securityStatus(String token) throws Exception {
        return mvc.perform(get(STATUS).header("Authorization", bearer(token)));
    }

    private ResultActions changeOwn(String token, String current, String next) throws Exception {
        return mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn().getResponse().getStatus();
    }

    // ── 1. warning for a legacy System Admin ───────────────────────────────────────────────────

    @Test
    void aSecureSystemAdminSeesNoWarning() throws Exception {
        securityStatus(sysadminToken()).andExpect(status().isOk()).andExpect(jsonPath("$.demoPasswordInUse").value(false));
    }

    @Test
    void aLegacySystemAdminCanStillSignInAndIsWarnedWithoutAnyAccountDetails() throws Exception {
        legacySysadmin("Admin12345");
        String legacyHash = hash("sysadmin");
        assertThat(loginStatus("sysadmin", "Admin12345")).as("not locked out").isEqualTo(200);
        String token = login("sysadmin", "Admin12345");
        MvcResult r = securityStatus(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.demoPasswordInUse").value(true)).andReturn();
        String body = r.getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"demoPasswordInUse\":true}")
                .doesNotContain("Admin12345").doesNotContain(legacyHash).doesNotContain("sysadmin");
        assertThat(hash("sysadmin")).as("nothing changed automatically").isEqualTo(legacyHash);
    }

    @Test
    void onlyTheSystemAdminCanReadTheWarning() throws Exception {
        legacySysadmin("Admin12345");
        securityStatus(shini.adminToken()).andExpect(status().isForbidden());
        securityStatus(shini.cashierToken()).andExpect(status().isForbidden());
        mvc.perform(get(STATUS)).andExpect(status().isUnauthorized());
    }

    @Test
    void changingTheLegacyPasswordRemovesTheWarning() throws Exception {
        legacySysadmin("Admin12345");
        String token = login("sysadmin", "Admin12345");
        securityStatus(token).andExpect(jsonPath("$.demoPasswordInUse").value(true));
        changeOwn(token, "Admin12345", "Owner-Chosen-Pass-42").andExpect(status().isNoContent());
        securityStatus(token).andExpect(jsonPath("$.demoPasswordInUse").value(false));
        assertThat(loginStatus("sysadmin", "Admin12345")).isEqualTo(401);
        assertThat(loginStatus("sysadmin", "Owner-Chosen-Pass-42")).isEqualTo(200);
    }

    // ── 2. demo passwords refused as a NEW administrator password ───────────────────────────────

    @Test
    void anAdministratorCannotChooseADemoPasswordForThemselves() throws Exception {
        String sysBefore = hash("sysadmin");
        String shiniBefore = hash("shini.admin");
        for (String demo : new String[]{"Admin12345", "admin12345", "ADMIN-PASS-1", "Admin-Pass-2", "Cashier-Pass-1"}) {
            changeOwn(sysadminToken(), SYSADMIN_PASSWORD, demo).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.newPassword").value(DemoPasswords.REJECTED_MESSAGE));
        }
        changeOwn(shini.adminToken(), STORE_ADMIN_PASSWORD, "admin-pass-2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").value(DemoPasswords.REJECTED_MESSAGE));
        assertThat(hash("sysadmin")).isEqualTo(sysBefore);
        assertThat(hash("shini.admin")).isEqualTo(shiniBefore);
        assertThat(loginStatus("sysadmin", SYSADMIN_PASSWORD)).isEqualTo(200);
        // the normal workflow and rules are unchanged
        changeOwn(shini.adminToken(), STORE_ADMIN_PASSWORD, "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("Password must be at least 8 characters"));
        changeOwn(shini.adminToken(), STORE_ADMIN_PASSWORD, "Shini-Owner-Pass-9").andExpect(status().isNoContent());
        assertThat(loginStatus("shini.admin", "Shini-Owner-Pass-9")).isEqualTo(200);
    }

    @Test
    void theSystemAdminCannotResetAStoreAdminToADemoPasswordButCashierResetsAreUnchanged() throws Exception {
        long adminId = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.admin'", Long.class);
        String before = hash("shini.admin");
        mvc.perform(put("/api/admin/stores/" + shini.storeId() + "/admins/" + adminId + "/password")
                        .header("Authorization", bearer(sysadminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Admin12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value(DemoPasswords.REJECTED_MESSAGE));
        assertThat(hash("shini.admin")).isEqualTo(before);
        mvc.perform(put("/api/admin/stores/" + shini.storeId() + "/admins/" + adminId + "/password")
                        .header("Authorization", bearer(sysadminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Brand-New-Owner-1\"}"))
                .andExpect(status().isOk());
        long cashierId = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.cashier'", Long.class);
        String adminToken = login("shini.admin", "Brand-New-Owner-1");
        mvc.perform(put("/api/store/cashiers/" + cashierId + "/password").header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Cashier-Pass-1\"}"))
                .andExpect(status().isOk()); // cashier passwords: unchanged behaviour (not an administrator)
    }
}
