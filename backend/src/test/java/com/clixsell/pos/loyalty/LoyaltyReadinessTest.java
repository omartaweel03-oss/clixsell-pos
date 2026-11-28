package com.clixsell.pos.loyalty;

import com.clixsell.pos.ApiTestBase;
import com.clixsell.pos.sale.SaleCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Requirement 8: the integration point a future loyalty module would use (no loyalty program is built). */
@Import(LoyaltyReadinessTest.Recorder.class)
class LoyaltyReadinessTest extends ApiTestBase {

    @TestConfiguration
    static class Recorder {
        static final List<SaleCompletedEvent> SEEN = new CopyOnWriteArrayList<>();

        @Bean
        Object saleCompletedRecorder() {
            return new Object() {
                @EventListener
                void on(SaleCompletedEvent e) {
                    SEEN.add(e);
                }
            };
        }
    }

    @Test
    void oneEventPerCompletedSale_withWhatWasPaid_noneForASuspension() throws Exception {
        Recorder.SEEN.clear();
        StoreSetup shop = setupStore("Loyal Store", "loyal");
        mvc.perform(put("/api/store/settings").header("Authorization", bearer(shop.adminToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"taxRate\":0,\"cashRoundingMinor\":10}")).andExpect(status().isOk());
        long p = createProduct(shop.adminToken(), "Oil", "4006381333931", 19.97, 10);
        String body = "{\"items\":[{\"productId\":" + p + ",\"quantity\":1}],\"paymentMethod\":\"CASH\"}";
        mvc.perform(post("/api/sales/suspended").header("Authorization", bearer(shop.cashierToken()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        assertThat(Recorder.SEEN).isEmpty();
        long sale = jsonLong(mvc.perform(post("/api/sales").header("Authorization", bearer(shop.cashierToken()))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn(), "saleId");
        assertThat(Recorder.SEEN).hasSize(1);
        SaleCompletedEvent e = Recorder.SEEN.get(0);
        assertThat(e.saleId()).isEqualTo(sale);
        assertThat(e.storeId()).isEqualTo(shop.storeId());
        assertThat(e.paymentMethod()).isEqualTo("CASH");
        assertThat(e.totalMinor()).isEqualTo(1997);
        assertThat(e.paidMinor()).isEqualTo(2000);
        // a refused sale publishes nothing
        mvc.perform(post("/api/sales").header("Authorization", bearer(shop.cashierToken()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"productId\":" + p
                        + ",\"quantity\":99}],\"paymentMethod\":\"CASH\"}")).andExpect(status().isConflict());
        assertThat(Recorder.SEEN).hasSize(1);
    }
}
