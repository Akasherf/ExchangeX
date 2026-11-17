package com.exchangex.api;

import com.exchangex.api.accounts.TradingClearingService;
import com.exchangex.api.auth.AuthService;
import com.exchangex.api.repository.*;
import com.exchangex.engine.MatchingEngine;
import com.exchangex.engine.Side;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Settlement and checkpoint MUST roll back together during injected failure. */
@ActiveProfiles("postgres-integration")
@SpringBootTest(classes = ExchangeApiApplication.class, properties = {
        "coordinator.auto-initialize=false", "spring.flyway.enabled=false",
        "exchangex.security.enabled=true", "exchangex.trading.enforced=true",
        "exchangex.multisymbol.enabled=false",
        "EXCHANGEX_JWT_SECRET_BASE64=VEVTVF9LRVlfT05MWV9FWENIQU5HRVhfMzJfQllURVNfMDE="})
@EnabledIfEnvironmentVariable(named="EXCHANGEX_TEST_DB_URL", matches=".*exchangex_test.*")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FundedRecoveryIntegrationTest {
    @Autowired AuthService auth;
    @Autowired TradingClearingService clearing;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommandRepository commands;
    @Autowired OrderRepository orders;
    @Autowired TradeRepository trades;
    @Autowired CheckpointRepository checkpoints;
    @Autowired TransactionTemplate tx;

    @BeforeAll
    static void migrate(@Autowired DataSource ds) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    private long user() {
        return auth.register(UUID.randomUUID()+"@example.test", "long-Strong-Password-123");
    }

    private DurableCommandCoordinator create(FailureInjection injection) {
        var c = new DurableCommandCoordinator(new MatchingEngine("AAPL"), commands, orders, trades,
                checkpoints, jdbc, tx, injection);
        c.configureTrading(clearing, true);
        return c;
    }

    private void completed(long sequence) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertEquals("COMPLETED", commands.findBySequenceId(sequence).orElseThrow().status()));
    }

    @Test
    void failureAfterSettlementWorkRollsBackEveryBalanceTradeAndCheckpoint() {
        trades.deleteAll();
        orders.deleteAll();
        commands.deleteAll();
        checkpoints.reset();
        long seller = user();
        long buyer = user();
        // Fixtures only; normal account mutations always go through AccountLedger.
        jdbc.update("UPDATE trading_accounts SET available_cash=1000 WHERE user_id=?", buyer);
        jdbc.update("INSERT INTO account_holdings (user_id,symbol,available_quantity) VALUES (?,?,?)",
                seller, "AAPL", 4);
        AtomicBoolean failNextTrade = new AtomicBoolean(false);
        FailureInjection injected = new FailureInjection.NoOp() {
            @Override public void afterTradesBeforeCommandStatus(long sequence) {
                if (failNextTrade.compareAndSet(true, false)) {
                    throw new IllegalStateException("injected settlement rollback");
                }
            }
        };
        DurableCommandCoordinator first = create(injected);
        try {
            first.initialize();
            long sell = first.admitSubmitForUser(seller, 902001, Side.SELL, 90, 4).sequenceId();
            completed(sell);
            failNextTrade.set(true);
            long buy = first.admitSubmitForUser(buyer, 902002, Side.BUY, 100, 2).sequenceId();
            await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                    assertEquals("FAILED", commands.findBySequenceId(buy).orElseThrow().status()));
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE buy_order_id=?",
                    Long.class, 902002));
            assertEquals(800L, jdbc.queryForObject("SELECT available_cash FROM trading_accounts WHERE user_id=?",
                    Long.class, buyer));
            assertEquals(200L, jdbc.queryForObject("SELECT reserved_cash FROM trading_accounts WHERE user_id=?",
                    Long.class, buyer));
            assertEquals(0L, jdbc.queryForObject("SELECT available_cash FROM trading_accounts WHERE user_id=?",
                    Long.class, seller));
            assertEquals(4L, jdbc.queryForObject("SELECT reserved_quantity FROM account_holdings WHERE user_id=? AND symbol=?",
                    Long.class, seller, "AAPL"));
        } finally {
            first.close();
        }
        DurableCommandCoordinator recovered = create(null);
        try {
            recovered.initialize();
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE buy_order_id=?",
                    Long.class, 902002));
            assertEquals(820L, jdbc.queryForObject("SELECT available_cash FROM trading_accounts WHERE user_id=?",
                    Long.class, buyer));
            assertEquals(0L, jdbc.queryForObject("SELECT reserved_cash FROM trading_accounts WHERE user_id=?",
                    Long.class, buyer));
            assertEquals(180L, jdbc.queryForObject("SELECT available_cash FROM trading_accounts WHERE user_id=?",
                    Long.class, seller));
            assertEquals(2L, jdbc.queryForObject("SELECT reserved_quantity FROM account_holdings WHERE user_id=? AND symbol=?",
                    Long.class, seller, "AAPL"));
            recovered.reinitialize();
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trades WHERE buy_order_id=?",
                    Long.class, 902002));
        } finally {
            recovered.close();
        }
    }
}
