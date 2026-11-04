package com.clixsell.pos;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, logout, lockout, deactivation, password reset and the role rules. */
class AuthAndRolesTest extends ApiTestBase {

    private org.springframework.test.web.servlet.ResultActions loginAttempt(String user, String pass) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(user, pass)));
    }

    // ── Login / logout ────────────────────────────────────────────────────

    @Test
    void loginReturnsTokenRoleAndStore() throws Exception {
        setupStore("Shini Supermarket", "shini");
        loginAttempt("SHINI.Cashier", "Cashier-Pass-1") // usernames ignore case
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.role").value("CASHIER"))
                .andExpect(jsonPath("$.user.storeName").value("Shini Supermarket"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        loginAttempt("sysadmin", SYSADMIN_PASSWORD)
                .andExpect(jsonPath("$.user.role").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.user.storeId").isEmpty());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
        setupStore("Shini Supermarket", "shini");
        loginAttempt("shini.cashier", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        loginAttempt("nobody.here", "whatever-1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void passwordsAreStoredOnlyAsBcryptHashes() throws Exception {
        setupStore("Shini Supermarket", "shini");
        String hash = db.queryForObject("SELECT password_hash FROM users WHERE username = 'shini.cashier'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("Cashier-Pass-1");
        String tokenHash = db.queryForObject("SELECT token_hash FROM user_sessions LIMIT 1", String.class);
        assertThat(tokenHash).hasSize(64); // SHA-256 hex, the token itself is not stored
    }

    @Test
    void requestsWithoutOrWithFakeTokenAreRejected() throws Exception {
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/products").header("Authorization", "Bearer made-up-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stores")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        mvc.perform(post("/api/auth/logout").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/products").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products").header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk()); // other users stay logged in
    }

    @Test
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        setupStore("Shini Supermarket", "shini");
        for (int i = 1; i <= 4; i++) {
            loginAttempt("shini.cashier", "wrong-" + i).andExpect(status().isUnauthorized());
        }
        loginAttempt("shini.cashier", "wrong-5")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        // Even the right password is refused while locked.
        loginAttempt("shini.cashier", "Cashier-Pass-1").andExpect(status().isTooManyRequests());
        assertThat(db.queryForObject("SELECT locked_until FROM users WHERE username = 'shini.cashier'", String.class))
                .isNotNull();

        // A password reset by the Store Admin unlocks it.
        long id = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.cashier'", Long.class);
        String admin = login("shini.admin", "Admin-Pass-1");
        mvc.perform(put("/api/store/cashiers/" + id + "/password").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"New-Pass-123\"}"))
                .andExpect(status().isOk());
        loginAttempt("shini.cashier", "New-Pass-123").andExpect(status().isOk());
    }

    // ── Deactivation and password reset ───────────────────────────────────

    @Test
    void deactivatedCashierIsLoggedOutAndCannotLogIn() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        long id = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.cashier'", Long.class);

        mvc.perform(put("/api/store/cashiers/" + id + "/status").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mvc.perform(get("/api/products").header("Authorization", bearer(shini.cashierToken())))
                .andExpect(status().isUnauthorized());
        loginAttempt("shini.cashier", "Cashier-Pass-1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mvc.perform(put("/api/store/cashiers/" + id + "/status").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        loginAttempt("shini.cashier", "Cashier-Pass-1").andExpect(status().isOk());
    }

    @Test
    void deactivatedStoreBlocksAllItsUsersButNotOtherStores() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        StoreSetup neshan = setupStore("Neshan Supermarket", "neshan");
        String sys = sysadminToken();

        mvc.perform(put("/api/admin/stores/" + shini.storeId() + "/status").header("Authorization", bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/products").header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isUnauthorized());
        loginAttempt("shini.cashier", "Cashier-Pass-1").andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        mvc.perform(get("/api/products").header("Authorization", bearer(neshan.cashierToken())))
                .andExpect(status().isOk());
    }

    @Test
    void passwordResetReplacesTheOldPasswordAndEndsSessions() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        long adminId = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.admin'", Long.class);
        mvc.perform(put("/api/admin/stores/" + shini.storeId() + "/admins/" + adminId + "/password")
                        .header("Authorization", bearer(sysadminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Brand-New-1\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/products").header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isUnauthorized());
        loginAttempt("shini.admin", "Admin-Pass-1").andExpect(status().isUnauthorized());
        loginAttempt("shini.admin", "Brand-New-1").andExpect(status().isOk());
    }

    // ── Role rules (backend-enforced) ─────────────────────────────────────

    @Test
    void cashierCanOnlyReadProductsAndOwnStore() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        String c = bearer(shini.cashierToken());

        mvc.perform(get("/api/products").header("Authorization", c)).andExpect(status().isOk());
        mvc.perform(get("/api/store").header("Authorization", c)).andExpect(status().isOk());

        mvc.perform(post("/api/products").header("Authorization", c).contentType(MediaType.APPLICATION_JSON)
                .content(productJson("Milk", "11111111", 5, 1, 1, "Co"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/products/1").header("Authorization", c).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/1").header("Authorization", c)).andExpect(status().isForbidden());
        mvc.perform(get("/api/products/generate-barcode").header("Authorization", c)).andExpect(status().isForbidden());
        mvc.perform(get("/api/store/cashiers").header("Authorization", c)).andExpect(status().isForbidden());
        mvc.perform(post("/api/store/cashiers").header("Authorization", c).contentType(MediaType.APPLICATION_JSON)
                .content(userJson("shini.extra", "Extra-Pass-1"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/stores").header("Authorization", c)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/stores").header("Authorization", c).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"My Shop\"}")).andExpect(status().isForbidden());

        assertThat(db.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'shini.extra'", Integer.class)).isZero();
    }

    @Test
    void storeAdminCannotManageStoresOrStoreAdmins() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        String a = bearer(shini.adminToken());
        mvc.perform(get("/api/admin/stores").header("Authorization", a)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/stores").header("Authorization", a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Another\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/stores/" + shini.storeId() + "/admins").header("Authorization", a)
                .contentType(MediaType.APPLICATION_JSON).content(userJson("shini.boss", "Boss-Pass-1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemAdminManagesStoresButNotProductsOrCashiers() throws Exception {
        String s = bearer(sysadminToken());
        mvc.perform(get("/api/products").header("Authorization", s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/store/cashiers").header("Authorization", s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/store").header("Authorization", s)).andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/stores").header("Authorization", s).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Shini Supermarket\",\"address\":\"Ramallah\",\"phone\":\"+970 2 123 4567\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.adminCount").value(0));
        mvc.perform(get("/api/admin/stores").header("Authorization", s))
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── Validation and duplicates ─────────────────────────────────────────

    @Test
    void duplicateUsernamesAndStoreNamesAreRejected() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        setupStore("Neshan Supermarket", "neshan");
        String sys = bearer(sysadminToken());

        // Usernames are unique across ALL stores.
        mvc.perform(post("/api/store/cashiers").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(userJson("Neshan.Cashier", "Some-Pass-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_USERNAME"))
                .andExpect(jsonPath("$.fieldErrors.username").exists());
        mvc.perform(post("/api/admin/stores").header("Authorization", sys).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"shini supermarket\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_STORE_NAME"));
    }

    @Test
    void invalidAccountsAndStoresAreRejected() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        mvc.perform(post("/api/store/cashiers").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a b\",\"fullName\":\"X\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.fullName").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
        mvc.perform(post("/api/admin/stores").header("Authorization", bearer(sysadminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"phone\":\"call me\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
    }
}
