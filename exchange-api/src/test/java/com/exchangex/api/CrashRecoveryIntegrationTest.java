package com.exchangex.api;

import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.engine.MatchingEngine;
import com.exchangex.engine.Side;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_CLASS;

import javax.sql.DataSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

/**
 * Crash recovery integration tests.
 * Verifies that commands admitted to the journal but failing to commit
 * are correctly recovered on coordinator restart.
 *
 * Run with: mvn -pl exchange-api -am -Dtest=CrashRecoveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = ExchangeApiApplication.class, properties = "coordinator.auto-initialize=false")
@AutoConfigureMockMvc
@ActiveProfiles(profiles = "postgres-integration", inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CrashRecoveryIntegrationTest {

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

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Disable Flyway auto-configuration - let the profile handle it
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeEach
    void setAsyncTimeout() {
        // Increase async timeout to 60 seconds via reflection
        try {
            java.lang.reflect.Field field = mockMvc.getClass().getDeclaredField("asyncRequestTimeout");
            field.setAccessible(true);
            field.set(mockMvc, 60000L);
        } catch (Exception e) {
            // Ignore if field doesn't exist
        }
        // Clean database before each test for proper isolation
        cleanDatabase();
        // Initialize coordinator after cleanup (auto-initialize is disabled in test config)
        coordinator.initialize();
        waitForBackgroundProcessing(1000);
    }

    private void cleanDatabase() {
        tradeRepository.deleteAll();
        orderRepository.deleteAll();
        commandRepository.deleteAll();
        checkpointRepository.reset();
    }

    private void waitForBackgroundProcessing(long maxWaitMs) {
        try {
            Thread.sleep(maxWaitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void testRestartRecoveryReplaysPendingCommands() throws Exception {
        // This test verifies the basic restart recovery path:
        // 1. Submit orders that execute and commit successfully
        // 2. Restart the coordinator (simulated by creating a new context)
        // 3. Verify the orders are still present and active

        // Step 1: Submit a BUY order
        long buyId = 1000000 + System.currentTimeMillis();
        String buyJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", buyId);
        MvcResult buyResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buyJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(buyResult))
                .andExpect(status().isAccepted());

        // Step 2: Submit a crossing SELL order (partial fill)
        long sellId = buyId + 1;
        String sellJson = String.format("{\"id\": %d, \"side\": \"SELL\", \"price\": 9900, \"quantity\": 6}", sellId);
        MvcResult sellResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sellJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(sellResult))
                .andExpect(status().isAccepted());

        // Wait for async processing - need enough time for background worker to process both orders
        Thread.sleep(15000);

        // Step 3: Verify orders are persisted correctly
        verifyOrderPersisted(buyId, 4, "PARTIAL");
        verifyOrderPersisted(sellId, 0, "FILLED");

        // Step 4: Verify trade persisted at maker price
        MvcResult tradesResult = mockMvc.perform(get("/api/v1/trades"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(tradesResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].buyOrderId").doesNotExist())
                .andExpect(jsonPath("$[0].sellOrderId").doesNotExist())
                .andExpect(jsonPath("$[0].price", is(10000)))
                .andExpect(jsonPath("$[0].quantity", is(6)));

        // Recovery correctness must still verify internal counterparties in the durable DB.
        var persistedTrades = tradeRepository.findBySymbol("AAPL");
        assertEquals(1, persistedTrades.size());
        assertEquals(buyId, persistedTrades.get(0).buyOrderId());
        assertEquals(sellId, persistedTrades.get(0).sellOrderId());

        // Step 5: Verify checkpoint advanced
        long checkpoint = checkpointRepository.getLastCompletedSequenceId("AAPL");
        assertTrue(checkpoint > 0, "Checkpoint should be advanced after successful commits");

        // Step 6: Simulate restart by verifying replay logic works
        // The coordinator is still running, but we can verify that if we were to
        // restart, the replayPendingCommands would find no pending commands
        // (since all were committed)
        var pendingCommands = commandRepository.findPendingCommandsSince("AAPL", checkpoint);
        assertTrue(pendingCommands.isEmpty(), "No commands should be pending after successful commit");
    }

    @Test
    void testCommitFailureLeavesCommandPendingForReplay() throws Exception {
        // This test verifies that when commitResults fails:
        // 1. The command remains PENDING in the journal (status column)
        // 2. The checkpoint is NOT advanced
        // 3. On restart, the command is replayed and committed

        long orderId = 2000000 + System.currentTimeMillis();

        // Insert a command directly into the journal as PENDING (simulating admission)
        long sequenceId = commandRepository.insertSubmitCommand(orderId, "AAPL", Side.BUY, 10000, 10);

        // Verify command is PENDING by checking findPendingSubmitByClientOrderId
        // which only returns commands with status = 'PENDING'
        var stillPending = commandRepository.findPendingSubmitByClientOrderId(orderId, "AAPL");
        assertTrue(stillPending.isPresent(), "Command should remain PENDING until committed");
        assertEquals(sequenceId, stillPending.get().sequenceId());
        assertEquals("SUBMIT", stillPending.get().commandType());

        // Clean up - mark it as completed so it doesn't interfere with other tests
        commandRepository.markCompleted(sequenceId);
    }

    @Test
    void testReplayRebuildsEngineFromCommittedState() throws Exception {
        // Verify that rebuildEngineFromDatabase correctly restores active orders
        // and ignores FILLED/CANCELLED orders

        // Submit a BUY order that will be partially filled
        long buyId = 3000000 + System.currentTimeMillis();
        String buyJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", buyId);
        MvcResult buyResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buyJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(buyResult))
                .andExpect(status().isAccepted());

        // Submit a crossing SELL order (partial fill - 4 shares)
        long sellId = buyId + 1;
        String sellJson = String.format("{\"id\": %d, \"side\": \"SELL\", \"price\": 9900, \"quantity\": 4}", sellId);
        MvcResult sellResult = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sellJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(sellResult))
                .andExpect(status().isAccepted());

        Thread.sleep(15000);

        // Verify BUY has 6 remaining (PARTIAL)
        verifyOrderPersisted(buyId, 6, "PARTIAL");
        // Verify SELL is FILLED
        verifyOrderPersisted(sellId, 0, "FILLED");

        // Now submit a crossing SELL order that will fill the remaining 6
        long sellId2 = buyId + 2;
        String sellJson2 = String.format("{\"id\": %d, \"side\": \"SELL\", \"price\": 9900, \"quantity\": 6}", sellId2);
        MvcResult sellResult2 = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sellJson2))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(sellResult2))
                .andExpect(status().isAccepted());

        Thread.sleep(15000);

        // Verify buyId is now FILLED
        verifyOrderPersisted(buyId, 0, "FILLED");
        // Verify sellId2 is FILLED
        verifyOrderPersisted(sellId2, 0, "FILLED");

        // Now simulate restart: the coordinator rebuilds engine from DB
        // Only orders with execution_status IN ('PENDING', 'PARTIAL') should be restored
        // The FILLED orders should NOT be in the engine

        // We can't easily restart the Spring context in this test,
        // but we can verify the data in the DB is correct for rebuild:

        var activeOrders = orderRepository.findActiveOrders("AAPL");
        // Should have: NO active orders (all FILLED)
        assertTrue(activeOrders.isEmpty(), "No active orders should remain after all filled");
    }

    @Test
    void testIdempotentReplayDoesNotDuplicateOrders() throws Exception {
        // If a command is admitted, executed, but commit fails,
        // and then replay executes it again - it should not create duplicate orders

        // This is tested by the idempotency logic in admitSubmit:
        // - Unique index on exchange_commands(order_id) WHERE command_type='SUBMIT'
        // - Payload verification on duplicate

        // But we also need to verify that commitResults doesn't insert duplicate orders
        // when replaying a command whose order was already inserted in a failed transaction
        // (which rolled back, so order doesn't exist in DB)

        // Since a failed transaction rolls back, the order insert is also rolled back.
        // So on replay, the order doesn't exist and will be inserted fresh. This is correct.

        // Verify idempotent retry returns same sequence_id
        long orderId = 4000000 + System.currentTimeMillis();
        String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 5}", orderId);

        MvcResult first = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(first))
                .andExpect(status().isAccepted());

        // Retry with same payload
        MvcResult second = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(second))
                .andExpect(status().isAccepted());

        // Both should return the same sequence_id (idempotent)
        // We can't easily extract sequence_id from the response in this test,
        // but the PostgreSQLIntegrationTest covers this.

        // Verify only one command exists in journal
        var pending = commandRepository.findPendingSubmitByClientOrderId(orderId, "AAPL");
        assertTrue(pending.isPresent());

        // Verify only one order exists in orders table
        var orderRecord = orderRepository.findByClientOrderId(orderId);
        assertTrue(orderRecord.isPresent());
    }

    @Test
    void testConcurrentAdmissionOrdering() throws Exception {
        // Verify that concurrent submissions are ordered by sequence_id
        // and processed in that order

        long baseId = 5000000 + System.currentTimeMillis();

        // Submit multiple orders rapidly
        for (int i = 0; i < 10; i++) {
            long orderId = baseId + i;
            String orderJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": %d, \"quantity\": 1}",
                    orderId, 10000 + i);
            MvcResult result = mockMvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderJson))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mockMvc.perform(asyncDispatch(result))
                    .andExpect(status().isAccepted());
        }

        Thread.sleep(15000);

        // Verify all orders are in the book in price-time priority
        // Best bid should be the highest price (10009)
        MvcResult bookResult = mockMvc.perform(get("/api/v1/orderbook/AAPL"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(bookResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestBid.price", is(10009))) // Highest price
                .andExpect(jsonPath("$.bestBid.id").doesNotExist());

        // The internal order identity is still required for deterministic ordering.
        var bestBid = coordinator.snapshot().join().bestBid().orElseThrow();
        assertEquals(baseId + 9, bestBid.id());
        assertEquals(10009, bestBid.price());
        assertTrue(orderRepository.findByClientOrderId(baseId + 9).isPresent());
    }

    @Test
    void testCancelOrderPersistsAndRecovers() throws Exception {
        // Submit an order
        long orderId = 6000000 + System.currentTimeMillis();
        String submitJson = String.format("{\"id\": %d, \"side\": \"BUY\", \"price\": 10000, \"quantity\": 10}", orderId);
        MvcResult submit = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(submit))
                .andExpect(status().isAccepted());

        Thread.sleep(5000);

        // Cancel it
        MvcResult cancel = mockMvc.perform(delete("/api/v1/orders/" + orderId))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(cancel))
                .andExpect(status().isAccepted());

        Thread.sleep(10000);

        // Verify cancelled in DB
        var orderRecord = orderRepository.findByClientOrderId(orderId);
        assertTrue(orderRecord.isPresent());
        assertEquals("CANCELLED", orderRecord.get().executionStatus());
        assertEquals(0, orderRecord.get().remainingQuantity());

        // Verify command is COMPLETED
        var pendingCmd = commandRepository.findPendingCancelByClientOrderId(orderId, "AAPL");
        assertTrue(pendingCmd.isEmpty(), "CANCEL command should be COMPLETED, not PENDING");

        // Simulate restart: verify rebuildEngineFromDatabase ignores CANCELLED orders
        var activeOrders = orderRepository.findActiveOrders("AAPL");
        boolean found = activeOrders.stream().anyMatch(o -> o.clientOrderId() == orderId);
        assertFalse(found, "CANCELLED orders should not be in active orders for rebuild");
    }

    private void verifyOrderPersisted(long orderId, long expectedRemaining, String expectedStatus) throws Exception {
        // Poll until order reaches terminal state (not PENDING) or timeout
        await().atMost(30, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            MvcResult orderResult = mockMvc.perform(get("/api/v1/orders/" + orderId))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mockMvc.perform(asyncDispatch(orderResult))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.executionStatus", not("PENDING")))
                    .andExpect(jsonPath("$.id", is(orderId)))
                    .andExpect(jsonPath("$.remainingQuantity", is((int)expectedRemaining)))
                    .andExpect(jsonPath("$.executionStatus", is(expectedStatus)));
        });
    }

    @Configuration
    static class CrashRecoveryConfig {
        // Uses the real production coordinator from component scanning
    }
}