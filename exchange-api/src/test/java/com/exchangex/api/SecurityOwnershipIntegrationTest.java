package com.exchangex.api;

import com.exchangex.api.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses the isolated PostgreSQL test DB; tests real JWT + journal ownership end-to-end. */
@ActiveProfiles("postgres-integration")
@SpringBootTest(classes = ExchangeApiApplication.class,
        properties = {"coordinator.auto-initialize=false", "spring.flyway.enabled=false",
                "exchangex.security.enabled=true", "EXCHANGEX_JWT_SECRET_BASE64=VEVTVF9LRVlfT05MWV9FWENIQU5HRVhfMzJfQllURVNfMDE="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".*exchangex_test.*")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SecurityOwnershipIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DurableCommandCoordinator coordinator;
    @Autowired CommandRepository commands;
    @Autowired OrderRepository orders;
    @Autowired TradeRepository trades;
    @Autowired CheckpointRepository checkpoint;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void migrate(@Autowired DataSource ds) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void reset() {
        trades.deleteAll();
        orders.deleteAll();
        commands.deleteAll(); // order_ownership FK is ON DELETE CASCADE
        checkpoint.reset();
        coordinator.initialize();
    }

    private String newEmail() { return UUID.randomUUID() + "@example.test"; }

    private String token(String email) throws Exception {
        String registration = mapper.writeValueAsString(new AccountCredentials(email, "long-Strong-Password-123"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isCreated());
        var response = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(registration))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + mapper.readTree(response.getResponse().getContentAsString()).get("accessToken").asText();
    }

    record AccountCredentials(String email, String password) {}

    @Test
    void anonymousOrderEndpointsRequireAuthentication() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":123,\"side\":\"BUY\",\"price\":100,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/123")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orderbook/AAPL")).andExpect(status().isOk());
    }

    @Test
    void ownershipIsAtomicAndCrossAccountActionsAreDenied() throws Exception {
        String alice = token(newEmail());
        String bob = token(newEmail());
        long id = System.currentTimeMillis() * 1000L;
        String requestBody = "{\"id\":" + id + ",\"side\":\"BUY\",\"price\":10000,\"quantity\":1}";
        MvcResult posted = mvc.perform(post("/api/v1/orders").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(posted)).andExpect(status().isAccepted());
        Integer owned = jdbc.queryForObject("SELECT COUNT(*) FROM order_ownership WHERE order_id = ?", Integer.class, id);
        assertEquals(1, owned);
        MvcResult blockedLookup = mvc.perform(get("/api/v1/orders/" + id).header("Authorization", bob))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(blockedLookup)).andExpect(status().isNotFound());
        MvcResult blockedCancel = mvc.perform(delete("/api/v1/orders/" + id).header("Authorization", bob))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(blockedCancel)).andExpect(status().isNotFound());
        MvcResult blockedRetry = mvc.perform(post("/api/v1/orders").header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(blockedRetry)).andExpect(status().isConflict());
    }

    @Test
    void sameOwnerRetryIsIdempotentAndCanCancel() throws Exception {
        String alice = token(newEmail());
        long id = System.currentTimeMillis() * 1000L;
        String body = "{\"id\":" + id + ",\"side\":\"BUY\",\"price\":10000,\"quantity\":5}";
        MvcResult first = mvc.perform(post("/api/v1/orders").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        String firstResponse = mvc.perform(asyncDispatch(first))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        MvcResult retried = mvc.perform(post("/api/v1/orders").header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        String retryResponse = mvc.perform(asyncDispatch(retried))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(firstResponse).get("sequenceId").asLong(),
                mapper.readTree(retryResponse).get("sequenceId").asLong());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_ownership WHERE order_id = ?", Integer.class, id));
        MvcResult cancellation = mvc.perform(delete("/api/v1/orders/" + id).header("Authorization", alice))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(cancellation)).andExpect(status().isAccepted());
    }

    @Test
    void invalidCredentialsAreRejected() throws Exception {
        String email = newEmail();
        token(email);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AccountCredentials(email, "wrong-password-12345"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/42").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }
}
