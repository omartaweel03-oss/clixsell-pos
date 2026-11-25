package com.clixsell.pos;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The approved demo profile keeps its demo Cashiers: allowed, and no warning for them. */
@TestPropertySource(properties = "clixsell.system-admin.allow-demo-password=true")
class CashierPasswordDemoProfileApiTest extends ApiTestBase {

    @Test
    void demoCashiersStayAllowedAndUnflaggedInTheDemoProfile() throws Exception {
        StoreSetup shini = setupStore("Shini Supermarket", "shini");
        mvc.perform(post("/api/store/cashiers").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content(userJson("shini.demo3", "Cashier-Pass-1")))
                .andExpect(status().isCreated());
        long id = db.queryForObject("SELECT user_id FROM users WHERE username = 'shini.cashier'", Long.class);
        mvc.perform(put("/api/store/cashiers/" + id + "/password").header("Authorization", bearer(shini.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"Cashier-Pass-1\"}"))
                .andExpect(status().isOk());
        db.update("UPDATE users SET password_hash = ? WHERE username = 'shini.demo3'",
                new BCryptPasswordEncoder().encode("Cashier-Pass-1"));
        mvc.perform(get("/api/store/cashiers/security-status").header("Authorization", bearer(shini.adminToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cashierIdsWithDemoPassword").isEmpty());
    }
}
