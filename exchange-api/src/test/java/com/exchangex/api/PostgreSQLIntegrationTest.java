package com.exchangex.api;

import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.engine.Side;
import com.exchangex.engine.Trade;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests against real PostgreSQL database.
 * Uses the database specified by EXCHANGEX_TEST_DB_URL (a dedicated test database).
 * Run with: mvn -pl exchange-api -am -Dtest=PostgreSQLIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ExchangeApiApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("postgres-integration")
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".+")
@Sql(scripts = "/test-cleanup.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PostgreSQLIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CommandRepository commandRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TradeRepository tradeRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private DataSource dataSource;

    private static final AtomicLong idGenerator = new AtomicLong(1000000 + System.currentTimeMillis());

    private long nextId() {
        return idGenerator.getAndIncrement();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Disable Flyway auto-configuration - we run migrations manually in @BeforeAll
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeAll
    static void runFlywayMigrations(@Autowired DataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();
    }

@Test
void submitDuplicateOrderIdDifferentPayload_returnsConflict() throws Exception {
    long orderId = nextId();
    String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);

    // First submission
    MvcResult first = mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(orderJson))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(first))
            .andExpect(status().isAccepted());

    // Duplicate with different price
    String differentJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 9900, \"quantity\": 10}", orderId);
    MvcResult second = mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(differentJson))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(second))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status", is("CONFLICT")));
}

@Test
void idempotentRetry_samePayload_returnsSameSequenceId() throws Exception {
    long orderId = nextId();
    String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 5}", orderId);

    MvcResult first = mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(orderJson))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(first))
            .andExpect(status().isAccepted());
    long firstSeq = extractSequenceId(first.getResponse().getContentAsString());

    // Retry with same payload
    MvcResult second = mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(orderJson))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(second))
            .andExpect(status().isAccepted());
    long secondSeq = extractSequenceId(second.getResponse().getContentAsString());

    assertEquals(firstSeq, secondSeq, "Idempotent retry should return same sequence_id");
}

@Test
void cancelOrder_persistsCancellation() throws Exception {
    long orderId = nextId();

    // Submit order
    String submitJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);
    MvcResult submit = mockMvc.perform(post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(submitJson))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(submit))
            .andExpect(status().isAccepted());

    Thread.sleep(200);

    // Cancel it
    MvcResult cancel = mockMvc.perform(delete("/api/v1/orders/" + orderId))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(cancel))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status", is("PENDING")));

    Thread.sleep(200);

    // Verify cancelled
    MvcResult orderResult = mockMvc.perform(get("/api/v1/orders/" + orderId))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc.perform(asyncDispatch(orderResult))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionStatus", is("CANCELLED")))
            .andExpect(jsonPath("$.remainingQuantity", is(0)));
}

private long extractSequenceId(String json) {
    // Simple extraction from {"status":"PENDING","sequenceId":123}
    int idx = json.indexOf("sequenceId");
    if (idx == -1) return -1;
    int start = json.indexOf(':', idx) + 1;
    int end = json.indexOf('}', start);
    if (end == -1) end = json.length();
    return Long.parseLong(json.substring(start, end).trim());
}
}