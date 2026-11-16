package com.exchangex.api;

import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.engine.Side;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Deterministic fault injection integration tests for transaction failure scenarios.
 * <p>
 * Tests verify that ExchangeX preserves durable command processing and database
 * consistency when execution transactions fail at various points.
 * <p>
 * Each test uses a fresh isolated database state via @Sql(BEFORE_TEST_METHOD).
 * Run with: mvn -pl exchange-api -am -Dtest=FaultInjectionIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ExchangeApiApplication.class, properties = {"spring.main.allow-bean-definition-overriding=true", "coordinator.auto-initialize=false"})
@AutoConfigureMockMvc
@ActiveProfiles(profiles = "postgres-integration", inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".+")
@Import(FailureInjectionTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FaultInjectionIntegrationTest {

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
    private DurableCommandCoordinator coordinator;

    @Autowired
    private FailureInjectionTestConfig.FailureInjectionControl failureControl;

    @Autowired
    private DataSource dataSource;

    private static final AtomicLong idGenerator = new AtomicLong(1000000 + System.currentTimeMillis());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(FaultInjectionIntegrationTest.class);

    private long nextId() {
        return idGenerator.getAndIncrement();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeEach
    void setAsyncTimeout() {
        try {
            java.lang.reflect.Field field = mockMvc.getClass().getDeclaredField("asyncRequestTimeout");
            field.setAccessible(true);
            field.set(mockMvc, 60000L);
        } catch (Exception e) {
            // Ignore if field doesn't exist
        }
        // Ensure failure injection is disabled by default
        failureControl.disable();
        // Clean database before each test (replaces @Sql which ran too late)
        cleanDatabase();
        // Initialize coordinator after cleanup (auto-initialize is disabled in test config)
        coordinator.initialize();
        waitForBackgroundProcessing(1000); // Allow initialize to complete
    }

    private void cleanDatabase() {
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        commandRepository.deleteAll();
        checkpointRepository.reset();
    }

    @BeforeAll
    static void runFlywayMigrations(@Autowired DataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        flyway.migrate();
    }

    // ============================================================
    // Helper: Submit order and extract sequenceId from response
    // ============================================================
    private record SubmissionResponse(String status, long sequenceId) {}

    private long submitOrderAndGetSequenceId(long clientOrderId, Side side, long price, long quantity) throws Exception {
        String orderJson = String.format("{\"id\": %d, \"side\": \"%s\", \"price\": %d, \"quantity\": %d}",
                clientOrderId, side.name(), price, quantity);
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult asyncResult = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted())
                .andReturn();

        String responseBody = asyncResult.getResponse().getContentAsString();
        SubmissionResponse response = objectMapper.readValue(responseBody, SubmissionResponse.class);
        return response.sequenceId();
    }

    /**
     * Submit an order to establish a baseline, then enable failure injection for the NEXT command.
     * Returns the sequenceId of the command that will be failed.
     */
    private long setupAndEnableFailureForNextCommand(String injectionPoint, Class<? extends RuntimeException> exceptionClass) throws Exception {
        // Submit a baseline BUY order that sits in the book (no trades)
        long setupId = nextId();
        long setupSequenceId = submitOrderAndGetSequenceId(setupId, Side.BUY, 10000, 10);

        // Wait for setup to fully commit
        waitForBackgroundProcessing(5000);

        // Enable failure for the NEXT sequence ID
        long targetSequenceId = setupSequenceId + 1;
        failureControl.enable(targetSequenceId, injectionPoint, exceptionClass);

        return targetSequenceId;
    }

    private void waitForBackgroundProcessing(long maxWaitMs) {
        try {
            Thread.sleep(maxWaitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // TEST 1: Atomic rollback - failure after orders, before trades
    // ============================================================
    @Test
    void testAtomicRollback_failureAfterOrdersBeforeTrades_noPartialCommits() throws Exception {
        // Setup: BUY order commits, then enable failure for next command (crossing SELL)
        long targetSequenceId = setupAndEnableFailureForNextCommand("afterOrdersBeforeTrades", RuntimeException.class);

        // Submit the crossing SELL order that will trigger the failure
        long sellId = nextId();
        String sellJson = String.format("{\"id\": %d, \"side\": \"SELL\", \"price\": 9900, \"quantity\": 5}", sellId);
        MvcResult sellResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sellJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(sellResult))
                .andExpect(status().isAccepted());

        // Wait for background worker to process and fail
        waitForBackgroundProcessing(10000);

        // Verify: The SELL command should be FAILED
        var sellCmd = commandRepository.findBySequenceId(targetSequenceId);
        assertTrue(sellCmd.isPresent(), "SELL command should exist in journal");
        assertEquals("FAILED", sellCmd.get().status(), "Command should be marked FAILED after commit failure");

        // Verify: NO partial commits from the failed transaction
        // 1. The SELL order should NOT exist in orders table (transaction rolled back)
        var failedSellOrder = orderRepository.findByClientOrderId(sellId);
        assertTrue(failedSellOrder.isEmpty(), "Failed command's order should not be committed (rolled back)");

        // 2. No trades for the failed command
        var allTrades = tradeRepository.findBySymbol("AAPL");
        boolean hasFailedTrade = allTrades.stream()
                .anyMatch(t -> t.buySequenceId() == targetSequenceId || t.sellSequenceId() == targetSequenceId);
        assertFalse(hasFailedTrade, "No trades should reference failed command's sequence ID");

        // 3. The BUY order should still exist (from successful first transaction)
        var setupBuyOrder = orderRepository.findByClientOrderId(sellId - 1); // setup ID was sellId - 1
        // Actually, we need to track the setup ID properly
        // Let's just verify there's at least one BUY order in the book
        var activeOrders = orderRepository.findActiveOrders("AAPL");
        assertFalse(activeOrders.isEmpty(), "BUY order from successful transaction should remain in book");

        // 4. Checkpoint should NOT have advanced past the failed command
        long checkpoint = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(targetSequenceId - 1, checkpoint,
                "Checkpoint should stay at last successful command, not advance past failed SELL");

        // Cleanup: disable failure injection
        failureControl.disable();
    }

    // ============================================================
    // TEST 2: Failure after durable admission, before execution commit
    // ============================================================
    @Test
    void testFailureAfterAdmission_durableJournalAvailableForRecovery() throws Exception {
        // Submit a baseline order first
        long setupId = nextId();
        long setupSequenceId = submitOrderAndGetSequenceId(setupId, Side.BUY, 10000, 10);
        waitForBackgroundProcessing(5000);

        // Enable failure for the NEXT command
        long targetSequenceId = setupSequenceId + 1;
        failureControl.enable(targetSequenceId, "afterTradesBeforeCommandStatus", RuntimeException.class);

        // Submit the order that will fail
        long orderId = nextId();
        String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted());

        // Wait for background worker to attempt processing
        waitForBackgroundProcessing(10000);

        // Debug: Check what happened
        var afterFailure = commandRepository.findBySequenceId(targetSequenceId);
        log.info("After failure - command status: {}", afterFailure.map(c -> c.status()).orElse("NOT_FOUND"));
        log.info("After failure - checkpoint: {}", checkpointRepository.getLastCompletedSequenceId("AAPL"));
        log.info("After failure - order exists: {}", orderRepository.findByClientOrderId(orderId).isPresent());
        var trades = tradeRepository.findBySymbol("AAPL");
        log.info("After failure - trades count: {}", trades.size());

        // Verify: Command should be FAILED (not COMPLETED)
        assertTrue(afterFailure.isPresent());
        assertEquals("FAILED", afterFailure.get().status(),
                "Command should be marked FAILED after execution transaction failure");

        // Verify: Checkpoint should not have advanced
        long checkpointAfter = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(setupSequenceId, checkpointAfter,
                "Checkpoint should not advance when commit fails");

        // Verify: No orders or trades committed for this command
        var order = orderRepository.findByClientOrderId(orderId);
        assertTrue(order.isEmpty(), "Order should not exist in DB after rolled back transaction");

        var trades2 = tradeRepository.findBySymbol("AAPL");
        assertTrue(trades2.isEmpty() || trades2.stream().noneMatch(t -> t.buySequenceId() == targetSequenceId || t.sellSequenceId() == targetSequenceId),
                "No trades should reference failed command's sequence ID");

        // Cleanup
        failureControl.disable();
        // Mark as completed so it doesn't interfere with other tests
        commandRepository.markCompleted(targetSequenceId);
    }

    // ============================================================
    // TEST 3: Restart and replay - new coordinator against same DB
    // ============================================================
    @Test
    void testRestartAndReplay_pendingCommandRecovered() throws Exception {
        // Phase 1: Submit a command that will fail during commit
        long setupId = nextId();
        long setupSequenceId = submitOrderAndGetSequenceId(setupId, Side.BUY, 10000, 10);
        waitForBackgroundProcessing(5000);

        long targetSequenceId = setupSequenceId + 1;
        failureControl.enable(targetSequenceId, "afterTradesBeforeCommandStatus", RuntimeException.class);

        long orderId = nextId();
        String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted());

        waitForBackgroundProcessing(10000);

        // Verify failure state - command should be FAILED
        var failedCmd = commandRepository.findBySequenceId(targetSequenceId);
        assertTrue(failedCmd.isPresent());
        assertEquals("FAILED", failedCmd.get().status(), "Command should be marked FAILED after commit failure");

        long checkpointAfterFailure = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(setupSequenceId, checkpointAfterFailure, "Checkpoint should not advance on failure");

        // Verify no order/trade was committed
        assertTrue(orderRepository.findByClientOrderId(orderId).isEmpty());

        // Phase 2: RESTART - disable failure, create new coordinator to replay
        failureControl.disable();
        coordinator.close();
        waitForBackgroundProcessing(2000);

        // Create new coordinator (simulating application restart)
        DurableCommandCoordinator newCoordinator = new DurableCommandCoordinator(
                new com.exchangex.engine.MatchingEngine("AAPL"),
                commandRepository,
                orderRepository,
                tradeRepository,
                checkpointRepository,
                (org.springframework.jdbc.core.JdbcTemplate) getField(coordinator, "jdbcTemplate"),
                new org.springframework.transaction.support.TransactionTemplate(getTransactionManager(coordinator)),
                new FailureInjection.NoOp()
        );

        // Initialize - this triggers rebuild and replay
        newCoordinator.initialize();
        waitForBackgroundProcessing(5000);

        // Phase 3: Verify recovery - pending command should be replayed and committed
        var recoveredCmd = commandRepository.findBySequenceId(targetSequenceId);
        assertTrue(recoveredCmd.isPresent());
        assertEquals("COMPLETED", recoveredCmd.get().status(),
                "After restart replay, command should be COMPLETED");

        // Checkpoint should have advanced
        long checkpointAfterRestart = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(targetSequenceId, checkpointAfterRestart,
                "Checkpoint should advance after successful replay");

        // Order should now exist in DB (replayed successfully)
        var orderAfterReplay = orderRepository.findByClientOrderId(orderId);
        assertTrue(orderAfterReplay.isPresent(), "Order should be persisted after replay");
        assertEquals("PENDING", orderAfterReplay.get().executionStatus()); // BUY order sits in book

        // Cleanup
        newCoordinator.close();
    }

    // ============================================================
    // TEST 4: Checkpoint safety - only advances after full commit
    // ============================================================
    @Test
    void testCheckpointSafety_failureBeforeCheckpoint_rolledBack() throws Exception {
        // Setup: BUY order commits
        long setupId = nextId();
        long setupSequenceId = submitOrderAndGetSequenceId(setupId, Side.BUY, 10000, 10);
        waitForBackgroundProcessing(5000);
        long checkpointAfterBuy = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(setupSequenceId, checkpointAfterBuy, "BUY should commit and advance checkpoint");

        // Enable failure for the NEXT command at checkpoint advance
        long targetSequenceId = setupSequenceId + 1;
        failureControl.enable(targetSequenceId, "afterCommandStatusBeforeCheckpoint", RuntimeException.class);

        // Submit crossing SELL order
        long sellId = nextId();
        String sellJson = String.format("{\"id\": %d, \"side\": \"SELL\", \"price\": 9900, \"quantity\": 5}", sellId);
        MvcResult sellResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sellJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(sellResult))
                .andExpect(status().isAccepted());

        // Wait for failure
        waitForBackgroundProcessing(10000);

        // Verify checkpoint did NOT advance past the BUY
        long checkpointAfter = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(setupSequenceId, checkpointAfter,
                "Checkpoint must not advance when failure occurs before checkpoint persistence (should stay at BUY)");

        // Verify the SELL command is FAILED, not COMPLETED
        var sellCmd = commandRepository.findBySequenceId(targetSequenceId);
        assertTrue(sellCmd.isPresent());
        assertEquals("FAILED", sellCmd.get().status(), "SELL command should be FAILED");

        // Verify no order/trade for the failed SELL
        assertTrue(orderRepository.findByClientOrderId(sellId).isEmpty());

        var trades = tradeRepository.findBySymbol("AAPL");
        boolean hasFailedTrade = trades.stream()
                .anyMatch(t -> t.buySequenceId() == targetSequenceId || t.sellSequenceId() == targetSequenceId);
        assertFalse(hasFailedTrade, "No trades for failed command");

        // Cleanup
        failureControl.disable();
    }

    // ============================================================
    // TEST 5: Replay idempotency - repeated recovery produces same state
    // ============================================================
    @Test
    void testReplayIdempotency_multipleRestartsSameResult() throws Exception {
        // Phase 1: Submit a command that will fail during execution commit
        long setupId = nextId();
        long setupSequenceId = submitOrderAndGetSequenceId(setupId, Side.BUY, 10000, 10);
        waitForBackgroundProcessing(5000);

        long targetSequenceId = setupSequenceId + 1;
        failureControl.enable(targetSequenceId, "afterTradesBeforeCommandStatus", RuntimeException.class);

        long orderId = nextId();
        String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isAccepted());

        waitForBackgroundProcessing(10000);

        // Verify failure state
        var failedCmd = commandRepository.findBySequenceId(targetSequenceId);
        assertTrue(failedCmd.isPresent());
        assertEquals("FAILED", failedCmd.get().status());

        long checkpointAfterFailure = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(setupSequenceId, checkpointAfterFailure);

        // Phase 2: FIRST RESTART - disable failure, let it recover
        failureControl.disable();
        coordinator.close();
        waitForBackgroundProcessing(2000);

        DurableCommandCoordinator coordinator1 = new DurableCommandCoordinator(
                new com.exchangex.engine.MatchingEngine("AAPL"),
                commandRepository,
                orderRepository,
                tradeRepository,
                checkpointRepository,
                (org.springframework.jdbc.core.JdbcTemplate) getField(coordinator, "jdbcTemplate"),
                new org.springframework.transaction.support.TransactionTemplate(getTransactionManager(coordinator)),
                new FailureInjection.NoOp()
        );
        coordinator1.initialize();
        waitForBackgroundProcessing(5000);

        // Verify first recovery
        var afterRecovery1 = commandRepository.findBySequenceId(targetSequenceId);
        assertEquals("COMPLETED", afterRecovery1.get().status());

        long checkpointAfterRecovery1 = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(targetSequenceId, checkpointAfterRecovery1);

        var orderAfter1 = orderRepository.findByClientOrderId(orderId);
        assertTrue(orderAfter1.isPresent());
        assertEquals("PENDING", orderAfter1.get().executionStatus());

        long tradeCountAfter1 = tradeRepository.findBySymbol("AAPL").size();

        // Phase 3: SECOND RESTART - simulate another crash/recovery cycle
        coordinator1.close();
        waitForBackgroundProcessing(2000);

        DurableCommandCoordinator coordinator2 = new DurableCommandCoordinator(
                new com.exchangex.engine.MatchingEngine("AAPL"),
                commandRepository,
                orderRepository,
                tradeRepository,
                checkpointRepository,
                (org.springframework.jdbc.core.JdbcTemplate) getField(coordinator, "jdbcTemplate"),
                new org.springframework.transaction.support.TransactionTemplate(getTransactionManager(coordinator)),
                new FailureInjection.NoOp()
        );
        coordinator2.initialize();
        waitForBackgroundProcessing(5000);

        // Verify second recovery produces IDENTICAL state
        var afterRecovery2 = commandRepository.findBySequenceId(targetSequenceId);
        assertEquals("COMPLETED", afterRecovery2.get().status(),
                "Command status should remain COMPLETED after second replay");

        long checkpointAfterRecovery2 = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertEquals(targetSequenceId, checkpointAfterRecovery2,
                "Checkpoint should not advance further on idempotent replay");

        var orderAfter2 = orderRepository.findByClientOrderId(orderId);
        assertTrue(orderAfter2.isPresent());
        assertEquals(orderAfter1.get().remainingQuantity(), orderAfter2.get().remainingQuantity(),
                "Order remaining quantity should be identical");
        assertEquals(orderAfter1.get().executionStatus(), orderAfter2.get().executionStatus(),
                "Order execution status should be identical");

        long tradeCountAfter2 = tradeRepository.findBySymbol("AAPL").size();
        assertEquals(tradeCountAfter1, tradeCountAfter2,
                "Trade count should not increase on idempotent replay (no duplicates)");

        // Cleanup
        coordinator2.close();
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private void verifyOrderPersisted(long orderId, long expectedRemaining, String expectedStatus) throws Exception {
        await().atMost(30, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            MvcResult orderResult = mockMvc.perform(get("/api/v1/orders/" + orderId))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mockMvc.perform(asyncDispatch(orderResult))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(orderId)))
                    .andExpect(jsonPath("$.remainingQuantity", is((int)expectedRemaining)))
                    .andExpect(jsonPath("$.executionStatus", is(expectedStatus)));
        });
    }

    private Object getField(Object target, String fieldName) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    private org.springframework.transaction.PlatformTransactionManager getTransactionManager(Object target) {
        try {
            Object txTemplate = getField(target, "transactionTemplate");
            if (txTemplate != null) {
                java.lang.reflect.Field tmField = txTemplate.getClass().getDeclaredField("transactionManager");
                tmField.setAccessible(true);
                return (org.springframework.transaction.PlatformTransactionManager) tmField.get(txTemplate);
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}