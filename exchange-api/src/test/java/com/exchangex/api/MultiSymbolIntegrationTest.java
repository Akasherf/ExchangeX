package com.exchangex.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.http.MediaType;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolation and per-instrument routing, with finance deliberately disabled for legacy integration. */
@ActiveProfiles("postgres-integration")
@SpringBootTest(classes = ExchangeApiApplication.class, properties = {
        "coordinator.auto-initialize=false", "spring.flyway.enabled=true",
        "exchangex.security.enabled=false", "exchangex.trading.enforced=false",
        "exchangex.multisymbol.enabled=true"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="EXCHANGEX_TEST_DB_URL", matches=".*exchangex_test.*")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MultiSymbolIntegrationTest {
    @Autowired SymbolCoordinatorRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private long submit(String symbol, long id, String side, long price) throws Exception {
        MvcResult initial = mvc.perform(post("/api/v1/instruments/"+symbol+"/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":"+id+",\"side\":\""+side+"\",\"price\":"+price+",\"quantity\":3}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(initial)).andExpect(status().isAccepted());
        return id;
    }

    /** Complete Spring MVC async processing before inspecting the public order-book JSON. */
    private ResultActions getBook(String symbol) throws Exception {
        MvcResult initial = mvc.perform(get("/api/v1/orderbook/" + symbol))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(initial));
    }

    @Test
    void independentBooksAndCancellationWithNoCrossInstrumentExecution() throws Exception {
        for (String symbol : new String[] {"MSFT", "GOOGL"}) {
            registry.coordinator(symbol).close();
            jdbc.update("DELETE FROM trades WHERE buy_order_id IN (SELECT order_id FROM orders WHERE symbol=?)", symbol);
            jdbc.update("DELETE FROM orders WHERE symbol=?", symbol);
            jdbc.update("DELETE FROM exchange_commands WHERE symbol=?", symbol);
            jdbc.update("UPDATE command_checkpoint SET last_completed_sequence_id=0 WHERE symbol=?", symbol);
            registry.coordinator(symbol).reinitialize();
        }
        mvc.perform(get("/api/v1/instruments")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("AAPL"))
                .andExpect(jsonPath("$[1]").value("GOOGL"))
                .andExpect(jsonPath("$[2]").value("MSFT"));
        submit("MSFT", 903001, "BUY", 120);
        submit("GOOGL", 903002, "BUY", 220);
        // Reusing a globally unique ID on another symbol must fail deterministically.
        MvcResult duplicate = mvc.perform(post("/api/v1/instruments/GOOGL/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":903001,\"side\":\"BUY\",\"price\":120,\"quantity\":3}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(duplicate)).andExpect(status().isConflict());
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            getBook("MSFT")
                    .andExpect(jsonPath("$.bestBid.price").value(120));
            getBook("GOOGL")
                    .andExpect(jsonPath("$.bestBid.price").value(220));
        });
        // Global order ID must not be accessible via the wrong instrument route.
        MvcResult wrong = mvc.perform(get("/api/v1/instruments/GOOGL/orders/903001"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(wrong)).andExpect(status().isNotFound());
        MvcResult cancellation = mvc.perform(delete("/api/v1/instruments/MSFT/orders/903001"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(cancellation)).andExpect(status().isAccepted());
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            getBook("MSFT")
                    .andExpect(jsonPath("$.bestBid").isEmpty());
            getBook("GOOGL")
                    .andExpect(jsonPath("$.bestBid.price").value(220));
        });
        mvc.perform(get("/api/v1/instruments/INVALID/trades")).andExpect(status().isNotFound());
    }
}
