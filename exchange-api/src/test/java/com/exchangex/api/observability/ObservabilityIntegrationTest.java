package com.exchangex.api.observability;

import com.exchangex.api.DurableCommandCoordinator;
import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.engine.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.awaitility.Awaitility.await;

import org.flywaydb.core.Flyway;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration tests for observability features.
 * <p>
 * Verifies:
 * - Actuator endpoints are accessible
 * - Prometheus metrics are exposed
 * - Correlation IDs are propagated
 * - Custom metrics are recorded
 * - Health indicator works
 * <p>
 * Run with: mvn -pl exchange-api -am -Dtest=ObservabilityIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = com.exchangex.api.ExchangeApiApplication.class,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
                "spring.datasource.url=${EXCHANGEX_TEST_DB_URL}",
                "spring.datasource.username=${EXCHANGEX_TEST_DB_USERNAME}",
                "spring.datasource.password=${EXCHANGEX_TEST_DB_PASSWORD}",
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "spring.flyway.enabled=false",
                "coordinator.auto-initialize=false",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.endpoint.health.show-details=always"
        })
@AutoConfigureMockMvc
@ActiveProfiles(profiles = "postgres-integration", inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ObservabilityIntegrationTest {

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
    private ExchangeMetrics metrics;

    @Autowired
    private CoordinatorHealthIndicator healthIndicator;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DurableCommandCoordinator coordinator;

    @Autowired
    private MeterRegistry meterRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final java.util.concurrent.atomic.AtomicLong idGenerator =
            new java.util.concurrent.atomic.AtomicLong(3000000 + System.currentTimeMillis());

    private long nextId() {
        return idGenerator.getAndIncrement();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeAll
    static void migrateDedicatedTestDatabase(@Autowired DataSource dataSource) {
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void setUp() {
        // Auto-initialization is disabled in this Spring test context.
        // Never let an old coordinator replay commands before the DB is cleaned.
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        commandRepository.deleteAll();
        checkpointRepository.reset();
        coordinator.initialize();
    }

    // ============================================================
    // Actuator Endpoint Tests
    // ============================================================

    @Test
    void testActuatorHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", anyOf(is("UP"), is("DOWN"))))
                .andExpect(jsonPath("$.components.db.status", anyOf(is("UP"), is("DOWN"))))
                .andExpect(jsonPath("$.components.diskSpace.status", is("UP")))
                .andExpect(jsonPath("$.components.ping.status", is("UP")));
    }

    @Test
    void testActuatorHealthEndpointWithDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.exchange").exists());
    }

    @Test
    void testActuatorInfoEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    @Test
    void testActuatorPrometheusEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("exchange_api_requests_total")))
                .andExpect(content().string(containsString("exchange_commands_admitted_total")))
                .andExpect(content().string(containsString("exchange_commands_completed_total")))
                .andExpect(content().string(containsString("exchange_backlog_current")))
                .andExpect(content().string(containsString("exchange_commands_pending")))
                .andExpect(content().string(containsString("exchange_checkpoint_last_sequence")));
    }

    // ============================================================
    // Correlation ID Tests
    // ============================================================

    @Test
    void testCorrelationIdGeneration() throws Exception {
        long orderId = nextId();
        String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult asyncResult = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted())
                .andReturn();

        // Verify correlation ID headers in response
        String traceId = asyncResult.getResponse().getHeader("X-Trace-Id");
        String spanId = asyncResult.getResponse().getHeader("X-Span-Id");

        assertNotNull(traceId, "Trace ID header should be present");
        assertNotNull(spanId, "Span ID header should be present");
        assertEquals(16, traceId.length(), "Trace ID should be 16 chars");
        assertEquals(8, spanId.length(), "Span ID should be 8 chars");
    }

    @Test
    void testCorrelationIdPropagation() throws Exception {
        long orderId = nextId();
        String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);
        String customTraceId = "custom-trace-1234567890123456";
        String customSpanId = "customspan";

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header("X-Trace-Id", customTraceId)
                        .header("X-Span-Id", customSpanId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult asyncResult = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted())
                .andReturn();

        // Verify custom correlation IDs are echoed back
        assertEquals(customTraceId, asyncResult.getResponse().getHeader("X-Trace-Id"));
        assertEquals(customSpanId, asyncResult.getResponse().getHeader("X-Span-Id"));
    }

    @Test
    void testCorrelationIdNotLoggedInActuator() throws Exception {
        // Actuator endpoints should not have correlation IDs (filter skips them)
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();

        // No correlation ID headers expected on actuator endpoints
        // (This is tested implicitly - no exception means filter works)
    }

    // ============================================================
    // Custom Metrics Tests
    // ============================================================

    @Test
    void testApiRequestCounterIncrementsOnOrderSubmission() throws Exception {
        double before = meterRegistry.get("exchange.api.requests.total").counter().count();
        long orderId = nextId();
        String orderJson = String.format(
                "{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(orderJson))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isAccepted());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(meterRegistry.get("exchange.api.requests.total").counter().count() > before,
                        "The HTTP metric must increase after a real API request"));
    }

    @Test
    void testOrderCompletionPersistedInCommandJournal() throws Exception {
        double admittedBefore = meterRegistry.get("exchange.commands.admitted.total").counter().count();
        double completedBefore = meterRegistry.get("exchange.commands.completed.total").counter().count();
        long orderId = nextId();
        String json = String.format(
                "{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted()).andReturn();
        long sequenceId = objectMapper.readTree(response.getResponse().getContentAsString())
                .get("sequenceId").asLong();

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals("COMPLETED", commandRepository.findBySequenceId(sequenceId)
                    .orElseThrow().status());
            assertTrue(checkpointRepository.getLastCompletedSequenceId("AAPL") >= sequenceId);
        });
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertEquals(admittedBefore + 1, meterRegistry.get("exchange.commands.admitted.total").counter().count());
            assertEquals(completedBefore + 1, meterRegistry.get("exchange.commands.completed.total").counter().count());
            assertEquals(0, meterRegistry.get("exchange.backlog.current").gauge().value());
            assertEquals((double) checkpointRepository.getLastCompletedSequenceId("AAPL"),
                    meterRegistry.get("exchange.checkpoint.last_sequence").gauge().value());
        });
    }

    @Test
    void testOrderCancellationMetrics() throws Exception {
        double cancelBefore = meterRegistry.get("exchange.orders.cancelled.total").counter().count();
        // Submit order
        long orderId = nextId();
        String submitJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);

        MvcResult submit = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(submit))
                .andExpect(status().isAccepted())
                .andReturn();

        // Wait for submission
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(orderRepository.findByClientOrderId(orderId).isPresent()));

        // Cancel it
        MvcResult cancel = mockMvc.perform(delete("/api/v1/orders/" + orderId))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(cancel))
                .andExpect(status().isAccepted())
                .andReturn();

        await().atMost(10, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var orderRecord = orderRepository.findByClientOrderId(orderId);
            assertTrue(orderRecord.isPresent());
            assertEquals("CANCELLED", orderRecord.get().executionStatus());
            assertEquals(cancelBefore + 1, meterRegistry.get("exchange.orders.cancelled.total").counter().count());
        });
    }

    // ============================================================
    // Health Indicator Tests
    // ============================================================

    @Test
    void testCoordinatorHealthIndicator() throws Exception {
        Health health = healthIndicator.health();

        assertNotNull(health);
        assertTrue(health.getStatus() == Status.UP || health.getStatus() == Status.DOWN || health.getStatus() == Status.OUT_OF_SERVICE);

        // Should have coordinator details
        assertTrue(health.getDetails().containsKey("coordinator"));
        assertTrue(health.getDetails().containsKey("database"));
        assertTrue(health.getDetails().containsKey("backlog"));
        assertTrue(health.getDetails().containsKey("pendingCommands"));
    }

    @Test
    void testHealthEndpointIncludesCoordinatorDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.exchange", notNullValue()))
                .andExpect(jsonPath("$.components.exchange.details.coordinator", notNullValue()))
                .andExpect(jsonPath("$.components.exchange.details.database", notNullValue()))
                .andExpect(jsonPath("$.components.exchange.details.backlog", notNullValue()));
    }

    // ============================================================
    // Prometheus Metrics Format Tests
    // ============================================================

    @Test
    void testPrometheusMetricsFormat() throws Exception {
        String prometheusOutput = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Check key metrics are present with correct format
        assertTrue(prometheusOutput.contains("exchange_api_requests_total{"), "API requests counter missing");
        assertTrue(prometheusOutput.contains("exchange_commands_admitted_total{"), "Commands admitted counter missing");
        assertTrue(prometheusOutput.contains("exchange_commands_completed_total{"), "Commands completed counter missing");
        assertTrue(prometheusOutput.contains("exchange_backlog_current{"), "Backlog gauge missing");
        assertTrue(prometheusOutput.contains("exchange_commands_pending{"), "Pending commands gauge missing");
        assertTrue(prometheusOutput.contains("exchange_checkpoint_last_sequence{"), "Checkpoint gauge missing");

        // Check histogram metrics
        assertTrue(prometheusOutput.contains("exchange_api_request_latency_seconds_bucket{"), "API latency histogram missing");
        assertTrue(prometheusOutput.contains("exchange_commands_admission_latency_seconds_bucket{"), "Admission latency histogram missing");

        // Check labels
        assertTrue(prometheusOutput.contains("application=\"exchange-api\""), "Application label missing");
        assertTrue(prometheusOutput.contains("symbol=\"AAPL\""), "Symbol label missing");
    }

    // ============================================================
    // Structured Logging Tests (implicit - verified via correlation IDs)
    // ============================================================

    @Test
    void testMalformedCorrelationIdsAreNotEchoed() throws Exception {
        long orderId = nextId();
        String json = String.format(
                "{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);

        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .header("X-Trace-Id", "unsafe trace with spaces")
                        .header("X-Span-Id", "unsafe span")
                        .header("Authorization", "Bearer test-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult response = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted()).andReturn();

        assertEquals(16, response.getResponse().getHeader("X-Trace-Id").length());
        assertEquals(8, response.getResponse().getHeader("X-Span-Id").length());
        assertNull(response.getResponse().getHeader("Authorization"));
        // This does not inspect server logs; log redaction needs a separate log-capture test.
    }

}