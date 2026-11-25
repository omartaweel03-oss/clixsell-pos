package com.clixsell.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Settings → Profile (report Figure 3.9): PUT /api/auth/me/password.
 * System Admin and Store Admin change their OWN password; Cashiers cannot
 * (their Store Admin resets it). Other sessions end; this one stays.
 */
class ChangePasswordApiTest extends ApiTestBase {

    private StoreSetup shini;
    private StoreSetup neshan;

    @BeforeEach
    void stores() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
    }

    private ResultActions change(String token, String current, String next) throws Exception {
        return mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next)));
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn().getResponse().getStatus();
    }

    private int meStatus(String token) throws Exception {
        return mvc.perform(get("/api/auth/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getStatus();
    }

    private String hash(String username) {
        return db.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);
    }

    @Test
    void storeAdminChangesOwnPasswordOtherSessionsEnd() throws Exception {
        String otherSession = login("shini.admin", STORE_ADMIN_PASSWORD);
        String before = hash("shini.admin");

        change(shini.adminToken(), STORE_ADMIN_PASSWORD, "New-Admin-Pass-2")
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));   // no password in the answer

        String after = hash("shini.admin");
        assertThat(after).isNotEqualTo(before).startsWith("$2").doesNotContain("New-Admin-Pass-2"); // BCrypt
        assertThat(meStatus(shini.adminToken())).isEqualTo(200);  // this session stays
        assertThat(meStatus(otherSession)).isEqualTo(401);        // the other one ended
        assertThat(loginStatus("shini.admin", STORE_ADMIN_PASSWORD)).isEqualTo(401);
        assertThat(loginStatus("shini.admin", "New-Admin-Pass-2")).isEqualTo(200);
        // Nobody else changed.
        assertThat(meStatus(shini.cashierToken())).isEqualTo(200);
        assertThat(meStatus(neshan.adminToken())).isEqualTo(200);
        assertThat(loginStatus("neshan.admin", STORE_ADMIN_PASSWORD)).isEqualTo(200);
    }

    @Test
    void systemAdminChangesOwnPassword() throws Exception {
        String original = hash("sysadmin");
        String sys = sysadminToken();
        try {
            change(sys, SYSADMIN_PASSWORD, "Sys-Admin-Changed-9").andExpect(status().isNoContent());
            assertThat(loginStatus("sysadmin", SYSADMIN_PASSWORD)).isEqualTo(401);
            assertThat(loginStatus("sysadmin", "Sys-Admin-Changed-9")).isEqualTo(200);
            assertThat(meStatus(sys)).isEqualTo(200);
        } finally {
            // Other tests log in with the original password.
            db.update("UPDATE users SET password_hash = ?, failed_login_attempts = 0, locked_until = NULL "
                    + "WHERE username = 'sysadmin'", original);
        }
    }

    @Test
    void cashierCannotChangeOwnPassword() throws Exception {
        String before = hash("shini.cashier");
        change(shini.cashierToken(), CASHIER_PASSWORD, "Cashier-New-Pass-2")
                .andExpect(status().isForbidden());
        assertThat(hash("shini.cashier")).isEqualTo(before);
        assertThat(meStatus(shini.cashierToken())).isEqualTo(200);  // still logged in
        assertThat(loginStatus("shini.cashier", CASHIER_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus("shini.cashier", "Cashier-New-Pass-2")).isEqualTo(401);
        // Not logged in at all → 401.
        mvc.perform(put("/api/auth/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"Whatever-123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void storeAdminResetsOwnCashierPasswordButNotAnotherStores() throws Exception {
        long shiniCashier = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.cashier'", Long.class);
        long neshanCashier = db.queryForObject("SELECT user_id FROM users WHERE username = 'neshan.cashier'", Long.class);

        mvc.perform(put("/api/store/cashiers/" + shiniCashier + "/password")
                        .header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Reset-By-Admin-1\"}"))
                .andExpect(status().isOk());
        assertThat(loginStatus("shini.cashier", "Reset-By-Admin-1")).isEqualTo(200);
        assertThat(meStatus(shini.cashierToken())).isEqualTo(401); // reset ends the cashier's sessions

        mvc.perform(put("/api/store/cashiers/" + neshanCashier + "/password")
                        .header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Taken-Over-1\"}"))
                .andExpect(status().isNotFound());
        // A cashier cannot reset passwords either.
        mvc.perform(put("/api/store/cashiers/" + shiniCashier + "/password")
                        .header("Authorization", bearer(neshan.cashierToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Taken-Over-1\"}"))
                .andExpect(status().isForbidden());
        assertThat(loginStatus("neshan.cashier", CASHIER_PASSWORD)).isEqualTo(200);
    }

    @Test
    void wrongCurrentPasswordIsRefusedWithoutLoggingOut() throws Exception {
        String before = hash("shini.admin");
        change(shini.adminToken(), "Wrong-Password", "New-Admin-Pass-2")
                .andExpect(status().isBadRequest())   // 400, not 401: the app must not log out
                .andExpect(jsonPath("$.fieldErrors.currentPassword").value("Current password is incorrect"));
        change(shini.adminToken(), "", "New-Admin-Pass-2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currentPassword").value("Current password is required"));
        assertThat(hash("shini.admin")).isEqualTo(before);
        assertThat(meStatus(shini.adminToken())).isEqualTo(200);
    }

    @Test
    void repeatedWrongCurrentPasswordsLockLikeLogin() throws Exception {
        for (int i = 0; i < 5; i++) {
            change(shini.adminToken(), "Wrong-" + i, "New-Admin-Pass-2").andExpect(status().isBadRequest());
        }
        // Locked: even the right current password is refused for now, and so is logging in.
        change(shini.adminToken(), STORE_ADMIN_PASSWORD, "New-Admin-Pass-2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currentPassword").value("Too many wrong attempts. Try again in a few minutes."));
        assertThat(loginStatus("shini.admin", STORE_ADMIN_PASSWORD)).isEqualTo(429);
        assertThat(loginStatus("shini.admin", "New-Admin-Pass-2")).isNotEqualTo(200);
    }

    @Test
    void newPasswordFollowsTheRules() throws Exception {
        String before = hash("shini.admin");
        change(shini.adminToken(), STORE_ADMIN_PASSWORD, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("Password must be at least 8 characters"));
        change(shini.adminToken(), STORE_ADMIN_PASSWORD, STORE_ADMIN_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("The new password must be different from the current one"));
        mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"" + STORE_ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("Password is required"));
        assertThat(hash("shini.admin")).isEqualTo(before);
    }

    @Test
    void onlyOwnPasswordChangesEvenIfAnotherUserIsNamed() throws Exception {
        String cashierBefore = hash("shini.cashier");
        long cashierId = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.cashier'", Long.class);
        mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":%d,\"username\":\"shini.cashier\",\"currentPassword\":\"Shop-Owner-Pass-1\",\"newPassword\":\"New-Admin-Pass-2\"}"
                                .formatted(cashierId)))
                .andExpect(status().isNoContent());
        assertThat(hash("shini.cashier")).isEqualTo(cashierBefore);
        assertThat(loginStatus("shini.cashier", CASHIER_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus("shini.admin", "New-Admin-Pass-2")).isEqualTo(200);
    }
}
