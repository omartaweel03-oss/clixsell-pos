package com.clixsell.pos;

import com.clixsell.pos.auth.DemoPasswords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Store Admins (outside the demo profile):
 * <ol>
 *   <li>a NEW Store Admin cannot be created with a published demo password (same rule as for
 *       password changes: {@link DemoPasswords}); legitimate passwords and Cashiers are unchanged;</li>
 *   <li>an EXISTING Store Admin that still uses one is warned about their own account
 *       ({@code GET /api/auth/me/security-status}), can still sign in, and nothing is changed.</li>
 * </ol>
 * "Existing" Store Admins are simulated by writing a demo-password hash into the TEST database only.
 */
class StoreAdminPasswordSecurityApiTest extends ApiTestBase {

    private static final String SELF_STATUS = "/api/auth/me/security-status";
    private StoreSetup shini;
    private StoreSetup neshan;

    @BeforeEach
    void stores() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
    }

    private ResultActions postStoreAdmin(String token, long storeId, String username, String password) throws Exception {
        return mvc.perform(post("/api/admin/stores/" + storeId + "/admins").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(userJson(username, password)));
    }

    private long users(String username) {
        return db.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, username);
    }

    private String hash(String username) {
        return db.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);
    }

    private ResultActions selfStatus(String token) throws Exception {
        return mvc.perform(get(SELF_STATUS).header("Authorization", bearer(token)));
    }

    // ── 1. account creation ─────────────────────────────────────────────────────────────────────

    @Test
    void aNewStoreAdminCannotGetADemoPassword() throws Exception {
        String sys = sysadminToken();
        for (String demo : new String[]{"Admin-Pass-1", "admin-pass-1", "ADMIN-PASS-2", "Admin12345", "Cashier-Pass-1"}) {
            postStoreAdmin(sys, shini.storeId(), "shini.owner2", demo).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.password").value(DemoPasswords.REJECTED_MESSAGE));
        }
        assertThat(users("shini.owner2")).as("nothing created").isZero();
    }

    @Test
    void aNewStoreAdminWithALegitimatePasswordIsCreatedAsBefore() throws Exception {
        postStoreAdmin(sysadminToken(), shini.storeId(), "shini.owner2", "Owner-Two-Pass-9")
                .andExpect(status().isCreated());
        assertThat(login("shini.owner2", "Owner-Two-Pass-9")).isNotBlank();
        // the existing account rules still apply first (unchanged)
        postStoreAdmin(sysadminToken(), shini.storeId(), "shini.owner3", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("Password must be at least 8 characters"));
        postStoreAdmin(sysadminToken(), shini.storeId(), "shini.owner2", "Another-Pass-9").andExpect(status().isConflict());
    }

    @Test
    void cashierAccountsAreUnchanged() throws Exception {
        // Cashiers are not administrators: the Store Admin may still use any valid password.
        createCashier(shini.adminToken(), "shini.cashier9", "Cashier-Pass-1");
        assertThat(users("shini.cashier9")).isEqualTo(1);
    }

    @Test
    void onlyTheSystemAdminMayCreateStoreAdminsAsBefore() throws Exception {
        postStoreAdmin(shini.adminToken(), shini.storeId(), "shini.owner2", "Owner-Two-Pass-9").andExpect(status().isForbidden());
        postStoreAdmin(shini.cashierToken(), shini.storeId(), "shini.owner2", "Owner-Two-Pass-9").andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/stores/" + shini.storeId() + "/admins").contentType(MediaType.APPLICATION_JSON)
                .content(userJson("shini.owner2", "Owner-Two-Pass-9"))).andExpect(status().isUnauthorized());
        assertThat(users("shini.owner2")).isZero();
    }

    // ── 2. existing Store Admins with a demo password ───────────────────────────────────────────

    @Test
    void anExistingStoreAdminWithADemoPasswordIsWarnedAboutTheirOwnAccountOnly() throws Exception {
        db.update("UPDATE users SET password_hash = ? WHERE username = 'shini.admin'",
                new BCryptPasswordEncoder().encode("Admin-Pass-1"));   // TEST database only
        String legacyHash = hash("shini.admin");
        String token = login("shini.admin", "Admin-Pass-1");                 // not locked out
        String body = selfStatus(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.demoPasswordInUse").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"demoPasswordInUse\":true}").doesNotContain("Admin-Pass-1")
                .doesNotContain(legacyHash).doesNotContain("shini");
        assertThat(hash("shini.admin")).as("nothing changed automatically").isEqualTo(legacyHash);
        // the other store's admin and the System Admin are not affected, and see only their own account
        selfStatus(neshan.adminToken()).andExpect(jsonPath("$.demoPasswordInUse").value(false));
        selfStatus(sysadminToken()).andExpect(jsonPath("$.demoPasswordInUse").value(false));
        // fixing it: own password change removes the warning; a demo replacement is still refused
        mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Admin-Pass-1\",\"newPassword\":\"admin12345\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"Admin-Pass-1\",\"newPassword\":\"Shini-Owner-Pass-5\"}"))
                .andExpect(status().isNoContent());
        selfStatus(token).andExpect(jsonPath("$.demoPasswordInUse").value(false));
    }

    @Test
    void aSecureStoreAdminSeesNoWarning() throws Exception {
        selfStatus(shini.adminToken()).andExpect(status().isOk()).andExpect(jsonPath("$.demoPasswordInUse").value(false));
    }

    @Test
    void roleAuthorizationOfTheStatusEndpointsIsUnchangedForOthers() throws Exception {
        selfStatus(shini.cashierToken()).andExpect(status().isForbidden());
        mvc.perform(get(SELF_STATUS)).andExpect(status().isUnauthorized());
        // the System Admin-only endpoint stays System Admin only
        mvc.perform(get("/api/admin/security-status").header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/security-status").header("Authorization", bearer(sysadminToken())))
                .andExpect(status().isOk());
    }
}
