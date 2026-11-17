package com.exchangex.api;

import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.engine.Side;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the production journal/coordinator, not just the in-memory processor. */
@SpringBootTest(classes = ExchangeApiApplication.class,
        properties = {"coordinator.auto-initialize=false", "spring.flyway.enabled=false"})
@ActiveProfiles(profiles = "postgres-integration", inheritProfiles = false)
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".+")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DurableConcurrencyIntegrationTest {
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);

    @Autowired DurableCommandCoordinator coordinator;
    @Autowired CommandRepository commands;
    @Autowired OrderRepository orders;
    @Autowired TradeRepository trades;
    @Autowired CheckpointRepository checkpoints;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    @BeforeAll
    static void migrate(@Autowired DataSource source) {
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void cleanThenStart() {
        trades.deleteAll();
        orders.deleteAll();
        commands.deleteAll();
        checkpoints.reset();
        coordinator.initialize();
    }

    private void completed(long sequence) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var cmd = commands.findBySequenceId(sequence).orElseThrow();
            assertEquals("COMPLETED", cmd.status(), "journal sequence=" + sequence);
        });
    }

    @Test
    void commitsAreReadFromJournalEvenWithoutInMemoryEnqueue() {
        long sell = IDS.incrementAndGet();
        long buy = IDS.incrementAndGet();
        // These INSERTs intentionally do not signal the coordinator. Journal polling
        // still must see them in order: the in-memory FIFO used to lose such work.
        long first = commands.insertSubmitCommand(sell, "AAPL", Side.SELL, 10_000, 1);
        long second = commands.insertSubmitCommand(buy, "AAPL", Side.BUY, 10_000, 1);
        assertTrue(second > first);
        completed(second);
        assertEquals(first, orders.findByClientOrderId(sell).orElseThrow().exchangeSequenceId());
        assertEquals(second, checkpoints.getLastCompletedSequenceId("AAPL"));
        assertEquals(1, trades.findBySymbol("AAPL").size());
        assertEquals(sell, trades.findBySymbol("AAPL").get(0).sellOrderId());
    }

    @Test
    void simultaneousUniqueSubmissionsCompleteInStrictSequenceOrder() throws Exception {
        int count = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<DurableCommandCoordinator.AdmissionResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                long id = IDS.incrementAndGet();
                futures.add(pool.submit(() -> {
                    go.await();
                    return coordinator.admitSubmit(id, Side.BUY, 9_900, 2);
                }));
            }
            go.countDown();
            Set<Long> sequences = new HashSet<>();
            long max = 0;
            for (Future<DurableCommandCoordinator.AdmissionResult> future : futures) {
                long seq = future.get(15, TimeUnit.SECONDS).sequenceId();
                assertTrue(sequences.add(seq), "unique admission sequence required");
                max = Math.max(max, seq);
            }
            long expectedMax = max;
            await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                    assertEquals(expectedMax, checkpoints.getLastCompletedSequenceId("AAPL")));
            for (long seq : sequences) completed(seq);
            assertEquals(0, trades.findBySymbol("AAPL").size());
            assertTrue(coordinator.snapshot().join().bestBid().isPresent());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIdenticalSubmitReturnsSingleSequenceId() throws Exception {
        long id = IDS.incrementAndGet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<DurableCommandCoordinator.AdmissionResult>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return coordinator.admitSubmit(id, Side.BUY, 9_900, 2);
                }));
            }
            go.countDown();
            long sequence = futures.get(0).get(15, TimeUnit.SECONDS).sequenceId();
            for (var future : futures) {
                assertEquals(sequence, future.get(15, TimeUnit.SECONDS).sequenceId());
            }
            completed(sequence);
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM exchange_commands WHERE order_id = ? AND command_type = 'SUBMIT'",
                    Long.class, id).longValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelImmediatelyAfterAdmissionCompletesWithoutRacingProjection() {
        long id = IDS.incrementAndGet();
        long submit = coordinator.admitSubmit(id, Side.BUY, 9_900, 5).sequenceId();
        long cancel = coordinator.admitCancel(id).sequenceId();
        assertTrue(cancel > submit);
        completed(cancel);
        assertEquals("CANCELLED", orders.findByClientOrderId(id).orElseThrow().executionStatus());
        assertEquals(0, orders.findByClientOrderId(id).orElseThrow().remainingQuantity());
        assertEquals(cancel, checkpoints.getLastCompletedSequenceId("AAPL"));
    }

    @Test
    void concurrentSnapshotsNeverReadMutableEngineCollections() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<?> writer = pool.submit(() -> {
                go.await();
                for (int i = 0; i < 30; i++) {
                    coordinator.admitSubmit(IDS.incrementAndGet(), Side.BUY, 9_900 + i, 1);
                }
                return null;
            });
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                readers.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 200; i++) {
                        var book = coordinator.snapshot().join();
                        assertNotNull(book.bestBid());
                        assertNotNull(book.bestAsk());
                        assertNotNull(coordinator.trades().join());
                    }
                    return null;
                }));
            }
            go.countDown();
            writer.get(15, TimeUnit.SECONDS);
            for (Future<?> reader : readers) reader.get(15, TimeUnit.SECONDS);
            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertEquals(30, jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class).longValue()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void admissionLockPreventsLaterSequenceFromCommittingFirst() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch permitCommit = new CountDownLatch(1);
        try {
            long firstId = IDS.incrementAndGet();
            long secondId = IDS.incrementAndGet();
            Future<Long> first = pool.submit(() -> transactions.execute(tx -> {
                jdbc.query("SELECT pg_advisory_xact_lock(27182, hashtext(?))", rs -> {}, "AAPL");
                long seq = commands.insertSubmitCommand(firstId, "AAPL", Side.BUY, 9_900, 1);
                firstInserted.countDown();
                try {
                    if (!permitCommit.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("Test did not release first transaction");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return seq;
            }));
            assertTrue(firstInserted.await(5, TimeUnit.SECONDS));
            Future<Long> second = pool.submit(() -> coordinator.admitSubmit(
                    secondId, Side.BUY, 9_900, 1).sequenceId());
            // Without the transaction-scoped admission lock, second can commit before first.
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            permitCommit.countDown();
            long firstSeq = first.get(10, TimeUnit.SECONDS);
            long secondSeq = second.get(10, TimeUnit.SECONDS);
            assertTrue(secondSeq > firstSeq);
            completed(secondSeq);
            assertEquals(secondSeq, checkpoints.getLastCompletedSequenceId("AAPL"));
        } finally {
            permitCommit.countDown();
            pool.shutdownNow();
        }
    }
}
