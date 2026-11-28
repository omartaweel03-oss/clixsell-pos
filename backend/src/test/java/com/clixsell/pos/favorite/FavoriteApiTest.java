package com.clixsell.pos.favorite;

import com.clixsell.pos.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Feature 5 (scenario C): Store Admin favorites with ordering, shown to the store's cashiers. */
class FavoriteApiTest extends ApiTestBase {

    private StoreSetup shop;
    private StoreSetup other;
    private long milk, bread, eggs, foreign;

    @BeforeEach
    void setUp() throws Exception {
        shop = setupStore("Fav Store", "fav");
        other = setupStore("Fav Other", "favother");
        milk = createProduct(shop.adminToken(), "Milk", "4006381333931", 5, 10);
        bread = createProduct(shop.adminToken(), "Bread", "5012345678900", 4, 10);
        eggs = createProduct(shop.adminToken(), "Eggs", "7290100000073", 11, 10);
        foreign = createProduct(other.adminToken(), "Other Milk", "4006381333931", 5, 10);
    }

    private ResultActions save(String token, String ids) throws Exception {
        return mvc.perform(put("/api/favorites").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":" + ids + "}"));
    }

    private List<Long> order() {
        return db.queryForList("SELECT product_id FROM product_favorites WHERE store_id = ? ORDER BY position", Long.class,
                shop.storeId());
    }

    @Test
    void adminSetsAndReordersFavorites_cashierSeesThemInOrder() throws Exception {
        save(shop.adminToken(), "[" + bread + "," + milk + "," + eggs + "]").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Bread")).andExpect(jsonPath("$[2].position").value(2));
        mvc.perform(get("/api/favorites").header("Authorization", bearer(shop.cashierToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(bread))
                .andExpect(jsonPath("$[1].productId").value(milk))
                .andExpect(jsonPath("$[2].productId").value(eggs));
        // move Eggs to the top, drop Milk
        save(shop.adminToken(), "[" + eggs + "," + bread + "]").andExpect(status().isOk());
        assertThat(order()).containsExactly(eggs, bread);
        save(shop.adminToken(), "[]").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(order()).isEmpty();
    }

    @Test
    void onlyTheStoreAdmin_onlyOwnProducts_noDuplicates_aFailedSaveChangesNothing() throws Exception {
        save(shop.adminToken(), "[" + milk + "," + bread + "]").andExpect(status().isOk());
        save(shop.cashierToken(), "[" + eggs + "]").andExpect(status().isForbidden());
        save(shop.adminToken(), "[" + eggs + "," + foreign + "]").andExpect(status().isNotFound());
        save(shop.adminToken(), "[" + eggs + "," + eggs + "]").andExpect(status().isBadRequest());
        save(shop.adminToken(), "null").andExpect(status().isBadRequest());
        assertThat(order()).containsExactly(milk, bread);
        // the other store has its own (empty) list
        mvc.perform(get("/api/favorites").header("Authorization", bearer(other.cashierToken())))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/favorites")).andExpect(status().isUnauthorized());
    }

    @Test
    void atMost40() throws Exception {
        StringBuilder ids = new StringBuilder("[");
        for (int i = 0; i < 41; i++) ids.append(i == 0 ? "" : ",").append(milk + 1000 + i);
        save(shop.adminToken(), ids.append("]").toString()).andExpect(status().isBadRequest());
    }
}
