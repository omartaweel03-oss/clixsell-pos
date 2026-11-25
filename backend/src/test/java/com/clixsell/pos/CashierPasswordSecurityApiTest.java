package com.clixsell.pos;

import com.clixsell.pos.auth.DemoPasswords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cashier account security (outside the demo profile):
 * <ol>
 *   <li>a Cashier cannot be created or reset with a published demo password (the same
 *       {@link DemoPasswords} rule as for administrators); a refusal changes nothing;</li>
 *   <li>the Store Admin sees which Cashiers of THEIR store still use one
 *       ({@code GET /api/store/cashiers/security-status}: ids only, own store only), resets them with
 *       the existing reset, and the warning disappears. Nothing is reset or locked automatically.</li>
 * </ol>
 * "Existing" Cashiers with a demo password are simulated by writing a demo-password hash into the
 * TEST database only.
 */
class CashierPasswordSecurityApiTest extends ApiTestBase {

    private static final String STATUS = "/api/store/cashiers/security-status";
    private static final String[] DEMO = {"Cashier-Pass-1", "cashier-pass-1", "CASHIER-PASS-1", "Admin12345", "Admin-Pass-2"};
    private StoreSetup shini;
    private StoreSetup neshan;

    @BeforeEach
    void stores() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        neshan = setupStore("Neshan Market", "neshan");
    }

    private ResultActions postCashier(String token, String username, String password) throws Exception {
        return mvc.perform(post("/api/store/cashiers").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(userJson(username, password)));
    }

    private ResultActions reset(String token, long cashierId, String password) throws Exception {
        return mvc.perform(put("/api/store/cashiers/" + cashierId + "/password").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + password + "\"}"));
    }

    private ResultActions securityStatus(String token) throws Exception {
        return mvc.perform(get(STATUS).header("Authorization", bearer(token)));
    }

    private long id(String username) {
        return db.queryForObject("SELECT user_id FROM users WHERE username = ?", Long.class, username);
    }

    private String hash(String username) {
        return db.queryForObject("SELECT password_hash FROM users WHERE username = ?", String.class, username);
    }

    private long users(String username) {
        return db.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, username);
    }

    /** TEST database only: an existing Cashier that still has a demo password. */
    private void legacyCashier(String username, String demoPassword) {
        db.update("UPDATE users SET password_hash = ? WHERE username = ?",
                new BCryptPasswordEncoder().encode(demoPassword), username);
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andReturn().getResponse().getStatus();
    }

    private int productsStatus(String token) throws Exception {
        return mvc.perform(get("/api/products").header("Authorization", bearer(token))).andReturn().getResponse().getStatus();
    }

    // ── creation ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void aCashierCannotBeCreatedWithADemoPassword() throws Exception {
        for (String demo : DEMO) {
            postCashier(shini.adminToken(), "shini.till", demo).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.password").value(DemoPasswords.REJECTED_MESSAGE));
        }
        assertThat(users("shini.till")).as("no account created").isZero();
    }

    @Test
    void aCashierWithASecurePasswordIsCreatedAsBefore() throws Exception {
        postCashier(shini.adminToken(), "shini.till", "Till-Cashier-Pass-4").andExpect(status().isCreated());
        assertThat(loginStatus("shini.till", "Till-Cashier-Pass-4")).isEqualTo(200);
        // the existing rules are unchanged and still come first
        postCashier(shini.adminToken(), "shini.till2", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("Password must be at least 8 characters"));
        postCashier(shini.adminToken(), "shini.till", "Another-Pass-9").andExpect(status().isConflict());
    }

    // ── reset ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aRefusedResetChangesNothingNoHashNoUnlockNoSessionEnded() throws Exception {
        long cashierId = id("shini.cashier");
        String before = hash("shini.cashier");
        for (String demo : DEMO) {
            reset(shini.adminToken(), cashierId, demo).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.password").value(DemoPasswords.REJECTED_MESSAGE));
        }
        assertThat(hash("shini.cashier")).isEqualTo(before);
        assertThat(productsStatus(shini.cashierToken())).as("the cashier's session was not ended").isEqualTo(200);
        // a locked cashier stays locked after a refused reset (no unlock either)
        for (int i = 0; i < 5; i++) loginStatus("shini.cashier", "Wrong-Pass-" + i);
        assertThat(loginStatus("shini.cashier", CASHIER_PASSWORD)).isEqualTo(429);
        reset(shini.adminToken(), cashierId, "Cashier-Pass-1").andExpect(status().isBadRequest());
        assertThat(loginStatus("shini.cashier", CASHIER_PASSWORD)).isEqualTo(429);
    }

    @Test
    void aSecureResetWorksAsBeforeEndsSessionsAndUnlocks() throws Exception {
        long cashierId = id("shini.cashier");
        for (int i = 0; i < 5; i++) loginStatus("shini.cashier", "Wrong-Pass-" + i);
        String oldSession = shini.cashierToken();
        reset(shini.adminToken(), cashierId, "Till-Cashier-Pass-4").andExpect(status().isOk());
        assertThat(productsStatus(oldSession)).as("old sessions end").isEqualTo(401);
        assertThat(loginStatus("shini.cashier", CASHIER_PASSWORD)).isEqualTo(401);
        assertThat(loginStatus("shini.cashier", "Till-Cashier-Pass-4")).as("unlocked").isEqualTo(200);
    }

    // ── existing cashiers with a demo password ──────────────────────────────────────────────────────

    @Test
    void existingDemoPasswordCashiersAreShownToTheirStoreAdminAndClearedBySecureReset() throws Exception {
        legacyCashier("shini.cashier", "Cashier-Pass-1");
        String legacyHash = hash("shini.cashier");
        long cashierId = id("shini.cashier");
        assertThat(loginStatus("shini.cashier", "Cashier-Pass-1")).as("not locked out").isEqualTo(200);

        String body = securityStatus(shini.adminToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"cashierIdsWithDemoPassword\":[" + cashierId + "]}")
                .doesNotContain("Cashier-Pass-1").doesNotContain(legacyHash).doesNotContain("shini.cashier");
        assertThat(hash("shini.cashier")).as("nothing changed automatically").isEqualTo(legacyHash);

        reset(shini.adminToken(), cashierId, "cashier-pass-1").andExpect(status().isBadRequest());
        securityStatus(shini.adminToken()).andExpect(jsonPath("$.cashierIdsWithDemoPassword[0]").value(cashierId));
        reset(shini.adminToken(), cashierId, "Till-Cashier-Pass-4").andExpect(status().isOk());
        securityStatus(shini.adminToken()).andExpect(jsonPath("$.cashierIdsWithDemoPassword").isEmpty());
    }

    @Test
    void secureCashiersGiveAnEmptyList() throws Exception {
        securityStatus(shini.adminToken()).andExpect(status().isOk())
                .andExpect(jsonPath("$.cashierIdsWithDemoPassword").isEmpty());
    }

    // ── authorization and isolation ────────────────────────────────────────────────────────────────

    @Test
    void onlyTheStoreAdminMayReadTheCashierWarnings() throws Exception {
        legacyCashier("shini.cashier", "Cashier-Pass-1");
        securityStatus(shini.cashierToken()).andExpect(status().isForbidden());
        securityStatus(sysadminToken()).andExpect(status().isForbidden());
        mvc.perform(get(STATUS)).andExpect(status().isUnauthorized());
        securityStatus(shini.adminToken()).andExpect(status().isOk());
        // creation and reset stay Store Admin only, as before
        postCashier(shini.cashierToken(), "shini.till", "Till-Cashier-Pass-4").andExpect(status().isForbidden());
        postCashier(sysadminToken(), "shini.till", "Till-Cashier-Pass-4").andExpect(status().isForbidden());
    }

    @Test
    void storesNeverSeeOrTouchEachOthersCashiers() throws Exception {
        legacyCashier("neshan.cashier", "Cashier-Pass-1");
        long neshanCashier = id("neshan.cashier");
        securityStatus(shini.adminToken()).andExpect(jsonPath("$.cashierIdsWithDemoPassword").isEmpty());
        securityStatus(neshan.adminToken()).andExpect(jsonPath("$.cashierIdsWithDemoPassword[0]").value(neshanCashier));
        String before = hash("neshan.cashier");
        // another store's cashier is "not found", whatever the password
        reset(shini.adminToken(), neshanCashier, "Cashier-Pass-1").andExpect(status().isNotFound());
        reset(shini.adminToken(), neshanCashier, "Till-Cashier-Pass-4").andExpect(status().isNotFound());
        assertThat(hash("neshan.cashier")).isEqualTo(before);
    }

    // ── performance with several cashiers ─────────────────────────────────────────────────────────

    @Test
    void manyCashiersAreCheckedOnceThenAnsweredFromTheCache() throws Exception {
        List<Long> demoIds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            createCashier(shini.adminToken(), "shini.c" + i, "Till-Pass-" + i + "-x");
            if (i % 4 == 0) {
                legacyCashier("shini.c" + i, "Cashier-Pass-1");
                demoIds.add(id("shini.c" + i));
            }
        }
        long t0 = System.nanoTime();
        String first = securityStatus(shini.adminToken()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long firstMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        String second = securityStatus(shini.adminToken()).andReturn().getResponse().getContentAsString();
        long secondMs = (System.nanoTime() - t0) / 1_000_000;
        // the normal list does no password check at all
        t0 = System.nanoTime();
        mvc.perform(get("/api/store/cashiers").header("Authorization", bearer(shini.adminToken()))).andExpect(status().isOk());
        long listMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("cashier security-status: 13 cashiers, first %d ms, second %d ms, list %d ms%n", firstMs, secondMs, listMs);
        assertThat(first).isEqualTo(second)
                .isEqualTo("{\"cashierIdsWithDemoPassword\":" + demoIds.toString().replace(" ", "") + "}");
        assertThat(secondMs).as("unchanged passwords are not checked again").isLessThan(Math.max(300, firstMs / 4));
        assertThat(listMs).isLessThan(1000);
    }
}
