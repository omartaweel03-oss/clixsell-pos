package com.clixsell.pos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The approved demo profile ({@code clixsell.system-admin.allow-demo-password=true}) keeps working:
 * demo passwords may be chosen (presentations, E2E tests on throwaway databases) and Settings shows
 * no warning for them.
 */
@TestPropertySource(properties = "clixsell.system-admin.allow-demo-password=true")
class AdminPasswordDemoProfileApiTest extends ApiTestBase {

    private StoreSetup shini;
    private String sysadminHash;

    @BeforeEach
    void setUp() throws Exception {
        shini = setupStore("Shini Supermarket", "shini");
        sysadminHash = db.queryForObject("SELECT password_hash FROM users WHERE username = 'sysadmin'", String.class);
    }

    @AfterEach
    void restoreSysadmin() {
        db.update("UPDATE users SET password_hash = ? WHERE username = 'sysadmin'", sysadminHash);
    }

    @Test
    void demoPasswordsStayUsableAndUnflaggedInTheDemoProfile() throws Exception {
        mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + STORE_ADMIN_PASSWORD + "\",\"newPassword\":\"Admin-Pass-2\"}"))
                .andExpect(status().isNoContent());
        String sys = sysadminToken();
        mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(sys))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + SYSADMIN_PASSWORD + "\",\"newPassword\":\"Admin12345\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/security-status").header("Authorization", bearer(sys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.demoPasswordInUse").value(false));
    }

    @Test
    void demoStoreAdminsCanStillBeCreatedInTheDemoProfile() throws Exception {
        mvc.perform(post("/api/admin/stores/" + shini.storeId() + "/admins").header("Authorization", bearer(sysadminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(userJson("shini.demo2", "Admin-Pass-1")))
                .andExpect(status().isCreated());
        String token = login("shini.demo2", "Admin-Pass-1");
        mvc.perform(get("/api/auth/me/security-status").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.demoPasswordInUse").value(false));
    }
}
