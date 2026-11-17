package com.exchangex.api.accounts;

import com.exchangex.api.ExchangeApiApplication;
import com.exchangex.api.auth.AuthService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("postgres-integration")
@SpringBootTest(classes = ExchangeApiApplication.class,
        properties = {"coordinator.auto-initialize=false", "spring.flyway.enabled=false",
                "exchangex.security.enabled=true",
                "EXCHANGEX_JWT_SECRET_BASE64=VEVTVF9LRVlfT05MWV9FWENIQU5HRVhfMzJfQllURVNfMDE="})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".*exchangex_test.*")
class AccountLedgerIntegrationTest {
    @Autowired AccountLedger ledger;
    @Autowired AuthService auth;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void migrate(@Autowired DataSource ds) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    private record User(long id, String token) {}

    private User createUser() {
        String email = UUID.randomUUID() + "@example.test";
        long id = auth.register(email, "long-Strong-Password-123");
        String token = "Bearer " + auth.login(email, "long-Strong-Password-123").accessToken();
        return new User(id, token);
    }

    @Test
    void registrationCreatesEmptyAccountAndPrivateReadEndpoints() throws Exception {
        User alice = createUser();
        User bob = createUser();
        assertEquals(new AccountLedger.CashBalance(0, 0), ledger.cashBalance(alice.id()));
        mvc.perform(get("/api/v1/account")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/account").header("Authorization", alice.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/v1/account/holdings").header("Authorization", alice.token()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        // Identity is always derived from the token, never a supplied account ID.
        ledger.creditSimulation(bob.id(), 800, "initial:bob");
        mvc.perform(get("/api/v1/account").header("Authorization", alice.token()))
                .andExpect(jsonPath("$.available").value(0));
    }

    @Test
    void cashCreditIsDurableAndIdempotentWithConflictDetection() {
        User account = createUser();
        ledger.creditSimulation(account.id(), 1_000, "seed:1");
        ledger.creditSimulation(account.id(), 1_000, "seed:1");
        assertEquals(new AccountLedger.CashBalance(1_000, 0), ledger.cashBalance(account.id()));
        assertThrows(IllegalStateException.class,
                () -> ledger.creditSimulation(account.id(), 2_000, "seed:1"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cash_ledger_events WHERE user_id = ?", Integer.class, account.id()));
    }

    @Test
    void cashReservationReleaseAndInsufficientFunds() {
        User account = createUser();
        ledger.creditSimulation(account.id(), 5_000, "seed");
        ledger.reserveCash(account.id(), 1_500, "order:123:reserve");
        assertEquals(new AccountLedger.CashBalance(3_500, 1_500), ledger.cashBalance(account.id()));
        assertThrows(AccountLedger.InsufficientBalanceException.class,
                () -> ledger.reserveCash(account.id(), 4_000, "order:124:reserve"));
        assertEquals(new AccountLedger.CashBalance(3_500, 1_500), ledger.cashBalance(account.id()));
        ledger.releaseCash(account.id(), 1_500, "order:123:release");
        ledger.releaseCash(account.id(), 1_500, "order:123:release");
        assertEquals(new AccountLedger.CashBalance(5_000, 0), ledger.cashBalance(account.id()));
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cash_ledger_events WHERE user_id = ?", Integer.class, account.id()));
    }

    @Test
    void holdingsReservationAndReleaseAreAtomic() throws Exception {
        User account = createUser();
        ledger.grantSimulationHolding(account.id(), "AAPL", 20, "seed:holding");
        ledger.reserveHolding(account.id(), "AAPL", 6, "order:501:reserve");
        assertThrows(AccountLedger.InsufficientBalanceException.class,
                () -> ledger.reserveHolding(account.id(), "AAPL", 15, "order:502:reserve"));
        assertEquals(new AccountLedger.Holding("AAPL", 14, 6), ledger.holdings(account.id()).getFirst());
        mvc.perform(get("/api/v1/account/holdings").header("Authorization", account.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].reserved").value(6));
        ledger.releaseHolding(account.id(), "AAPL", 6, "order:501:release");
        assertEquals(new AccountLedger.Holding("AAPL", 20, 0), ledger.holdings(account.id()).getFirst());
    }

    @Test
    void concurrentCreditsSerializeWithoutLosingUpdates() throws Exception {
        User account = createUser();
        int workers = 8;
        int events = 24;
        CountDownLatch begin = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < events; i++) {
                String key = "concurrent:" + i;
                futures.add(pool.submit(() -> {
                    begin.await();
                    ledger.creditSimulation(account.id(), 50, key);
                    return null;
                }));
            }
            begin.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }
        assertEquals(new AccountLedger.CashBalance(1_200, 0), ledger.cashBalance(account.id()));
        assertEquals(events, jdbc.queryForObject(
                "SELECT COUNT(*) FROM cash_ledger_events WHERE user_id = ?", Integer.class, account.id()));
    }

    @Test
    void overflowAndInvalidInputDoNotMutateBalance() {
        User account = createUser();
        assertThrows(IllegalArgumentException.class,
                () -> ledger.creditSimulation(account.id(), 0, "zero"));
        ledger.creditSimulation(account.id(), Long.MAX_VALUE - 100, "large");
        assertThrows(ArithmeticException.class,
                () -> ledger.creditSimulation(account.id(), 101, "overflow"));
        assertEquals(new AccountLedger.CashBalance(Long.MAX_VALUE - 100, 0), ledger.cashBalance(account.id()));
    }
}
