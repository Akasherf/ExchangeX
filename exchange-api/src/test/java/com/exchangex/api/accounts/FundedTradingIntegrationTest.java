package com.exchangex.api.accounts;

import com.exchangex.api.DurableCommandCoordinator;
import com.exchangex.api.ExchangeApiApplication;
import com.exchangex.api.auth.AuthService;
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

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL financial integration tests; never run against production data. */
@ActiveProfiles("postgres-integration")
@SpringBootTest(classes = ExchangeApiApplication.class,
        properties = {"coordinator.auto-initialize=false", "spring.flyway.enabled=false",
                "exchangex.security.enabled=true", "exchangex.trading.enforced=true",
                "exchangex.multisymbol.enabled=false",
                "EXCHANGEX_JWT_SECRET_BASE64=VEVTVF9LRVlfT05MWV9FWENIQU5HRVhfMzJfQllURVNfMDE="})
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".*exchangex_test.*")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FundedTradingIntegrationTest {
    @Autowired AccountLedger accounts;
    @Autowired AuthService users;
    @Autowired DurableCommandCoordinator coordinator;
    @Autowired JdbcTemplate jdbc;
    @Autowired TradeRepository trades;
    @Autowired OrderRepository orders;
    @Autowired CommandRepository commands;
    @Autowired CheckpointRepository checkpoints;

    @BeforeAll
    static void migrate(@Autowired DataSource ds) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void reset() {
        trades.deleteAll();
        orders.deleteAll();
        commands.deleteAll();
        checkpoints.reset();
        coordinator.initialize();
    }

    private long newUser() {
        return users.register(UUID.randomUUID() + "@example.test", "long-Strong-Password-123");
    }

    private void committed(long sequence) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var cmd = commands.findBySequenceId(sequence).orElseThrow();
            assertEquals("COMPLETED", cmd.status());
        });
    }

    private long submit(long account, long orderId, Side side, long price, long quantity) {
        return coordinator.admitSubmitForUser(account, orderId, side, price, quantity).sequenceId();
    }

    @Test
    void unfundedOrdersAndOversellsFailBeforeJournalAdmission() {
        long buyer = newUser();
        long seller = newUser();
        assertThrows(AccountLedger.InsufficientBalanceException.class,
                () -> submit(buyer, 901001, Side.BUY, 100, 5));
        assertThrows(AccountLedger.InsufficientBalanceException.class,
                () -> submit(seller, 901002, Side.SELL, 100, 5));
        assertTrue(commands.findPendingSubmitByClientOrderId(901001, "AAPL").isEmpty());
        assertTrue(commands.findPendingSubmitByClientOrderId(901002, "AAPL").isEmpty());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trading_order_reservations", Long.class));
    }

    @Test
    void partialFillPriceImprovementAndCancellationConserveCashAndShares() {
        long buyer = newUser();
        long seller = newUser();
        accounts.creditSimulation(buyer, 2_000, "seed:cash");
        accounts.grantSimulationHolding(seller, "AAPL", 5, "seed:stocks");
        long sellSeq = submit(seller, 901101, Side.SELL, 90, 5);
        committed(sellSeq);
        assertEquals(new AccountLedger.Holding("AAPL", 0, 5), accounts.holdings(seller).getFirst());
        long buySeq = submit(buyer, 901102, Side.BUY, 100, 2);
        committed(buySeq);
        assertEquals(new AccountLedger.CashBalance(1820, 0), accounts.cashBalance(buyer));
        assertEquals(new AccountLedger.CashBalance(180, 0), accounts.cashBalance(seller));
        assertEquals(new AccountLedger.Holding("AAPL", 2, 0), accounts.holdings(buyer).getFirst());
        assertEquals(new AccountLedger.Holding("AAPL", 0, 3), accounts.holdings(seller).getFirst());
        long cancelSeq = coordinator.admitCancelForUser(seller, 901101).sequenceId();
        committed(cancelSeq);
        assertEquals(new AccountLedger.Holding("AAPL", 3, 0), accounts.holdings(seller).getFirst());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT reserved_remaining FROM trading_order_reservations WHERE order_id=?",
                Long.class, 901101));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE buy_order_id=? AND sell_order_id=?",
                Long.class, 901102, 901101));
        coordinator.reinitialize();
        assertEquals(new AccountLedger.CashBalance(1820, 0), accounts.cashBalance(buyer));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE buy_order_id=? AND sell_order_id=?",
                Long.class, 901102, 901101));
    }

    @Test
    void sameAccountCrossingRestingOrderIsRejectedWithoutJournalOrLedgerMutation() {
        long user = newUser();
        accounts.creditSimulation(user, 2_000, "seed:cash");
        accounts.grantSimulationHolding(user, "AAPL", 5, "seed:holding");
        long sellSequence = submit(user, 902101, Side.SELL, 90, 5);
        committed(sellSequence);

        assertThrows(TradingClearingService.SelfTradePreventedException.class,
                () -> submit(user, 902102, Side.BUY, 100, 2));
        assertTrue(commands.findPendingSubmitByClientOrderId(902102, "AAPL").isEmpty());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_ownership WHERE order_id=?", Long.class, 902102));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trading_order_reservations WHERE order_id=?", Long.class, 902102));
        assertEquals(new AccountLedger.CashBalance(2000, 0), accounts.cashBalance(user));
        assertEquals(new AccountLedger.Holding("AAPL", 0, 5), accounts.holdings(user).getFirst());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trades", Long.class));
    }

    @Test
    void sameAccountCrossingSellAgainstOwnBidIsRejected() {
        long user = newUser();
        accounts.creditSimulation(user, 1000, "seed:cash");
        accounts.grantSimulationHolding(user, "AAPL", 3, "seed:holding");
        committed(submit(user, 902151, Side.BUY, 110, 2));
        assertThrows(TradingClearingService.SelfTradePreventedException.class,
                () -> submit(user, 902152, Side.SELL, 100, 1));
        assertTrue(commands.findPendingSubmitByClientOrderId(902152, "AAPL").isEmpty());
        assertEquals(new AccountLedger.CashBalance(780, 220), accounts.cashBalance(user));
        assertEquals(new AccountLedger.Holding("AAPL", 3, 0), accounts.holdings(user).getFirst());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trades", Long.class));
    }

    @Test
    void sameAccountNonCrossingOrdersAreAllowedAndCancellingReleasesBoth() {
        long user = newUser();
        accounts.creditSimulation(user, 1000, "seed:cash");
        accounts.grantSimulationHolding(user, "AAPL", 5, "seed:holding");
        long buy = submit(user, 902201, Side.BUY, 90, 2);
        long sell = submit(user, 902202, Side.SELL, 100, 3);
        committed(buy);
        committed(sell);
        assertEquals(new AccountLedger.CashBalance(820, 180), accounts.cashBalance(user));
        assertEquals(new AccountLedger.Holding("AAPL", 2, 3), accounts.holdings(user).getFirst());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trades", Long.class));
        committed(coordinator.admitCancelForUser(user, 902201).sequenceId());
        committed(coordinator.admitCancelForUser(user, 902202).sequenceId());
        assertEquals(new AccountLedger.CashBalance(1000, 0), accounts.cashBalance(user));
        assertEquals(new AccountLedger.Holding("AAPL", 5, 0), accounts.holdings(user).getFirst());
    }

    @Test
    void pendingOwnOppositeSubmitAlsoPreventsCrossingBeforeProjection() {
        long user = newUser();
        accounts.creditSimulation(user, 1000, "seed:cash");
        accounts.grantSimulationHolding(user, "AAPL", 5, "seed:holding");
        // The worker may finish before the second call; either the pending
        // journal or the resting order projection must trigger the guard.
        submit(user, 902301, Side.SELL, 90, 3);
        assertThrows(TradingClearingService.SelfTradePreventedException.class,
                () -> submit(user, 902302, Side.BUY, 100, 1));
        assertTrue(commands.findPendingSubmitByClientOrderId(902302, "AAPL").isEmpty());
    }

    @Test
    void oppositeSideFromDifferentAccountCanStillMatch() {
        long buyer = newUser();
        long seller = newUser();
        accounts.creditSimulation(buyer, 1000, "seed:cash");
        accounts.grantSimulationHolding(seller, "AAPL", 3, "seed:holding");
        committed(submit(seller, 902401, Side.SELL, 90, 3));
        committed(submit(buyer, 902402, Side.BUY, 100, 2));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trades", Long.class));
        assertEquals(new AccountLedger.CashBalance(820, 0), accounts.cashBalance(buyer));
        assertEquals(new AccountLedger.CashBalance(180, 0), accounts.cashBalance(seller));
    }

    @Test
    void duplicateSubmissionDoesNotReserveTwiceAndConflictingPayloadFails() {
        long buyer = newUser();
        accounts.creditSimulation(buyer, 1000, "seed");
        long first = submit(buyer, 901201, Side.BUY, 100, 4);
        long retry = submit(buyer, 901201, Side.BUY, 100, 4);
        assertEquals(first, retry);
        assertThrows(IllegalArgumentException.class,
                () -> submit(buyer, 901201, Side.BUY, 100, 3));
        assertEquals(new AccountLedger.CashBalance(600, 400), accounts.cashBalance(buyer));
        committed(first);
        long cancel = coordinator.admitCancelForUser(buyer, 901201).sequenceId();
        committed(cancel);
        assertEquals(new AccountLedger.CashBalance(1000, 0), accounts.cashBalance(buyer));
    }

    @Test
    void twoConcurrentOrdersCannotSpendTheSameCashTwice() throws Exception {
        long buyer = newUser();
        accounts.creditSimulation(buyer, 100, "seed");
        var gate = new CountDownLatch(1);
        var success = new AtomicInteger();
        var rejected = new AtomicInteger();
        CompletableFuture<?> a = CompletableFuture.runAsync(() -> concurrentSubmit(gate, buyer, 901301, success, rejected));
        CompletableFuture<?> b = CompletableFuture.runAsync(() -> concurrentSubmit(gate, buyer, 901302, success, rejected));
        gate.countDown();
        CompletableFuture.allOf(a, b).get(20, TimeUnit.SECONDS);
        assertEquals(1, success.get());
        assertEquals(1, rejected.get());
        assertEquals(new AccountLedger.CashBalance(0, 100), accounts.cashBalance(buyer));
    }

    private void concurrentSubmit(CountDownLatch gate, long account, long id,
                                  AtomicInteger success, AtomicInteger rejected) {
        try {
            gate.await();
            submit(account, id, Side.BUY, 100, 1);
            success.incrementAndGet();
        } catch (AccountLedger.InsufficientBalanceException e) {
            rejected.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
