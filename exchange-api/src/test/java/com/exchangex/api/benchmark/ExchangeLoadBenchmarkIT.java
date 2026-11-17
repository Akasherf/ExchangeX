package com.exchangex.api.benchmark;

import com.exchangex.api.DurableCommandCoordinator;
import com.exchangex.api.ExchangeApiApplication;
import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.CommandRepository.CommandRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Opt-in Spring MVC + PostgreSQL benchmark. NOT JMH and not a network benchmark.
 * Its name deliberately does not match Surefire default *Test conventions.
 * Run explicitly using -Dtest=ExchangeLoadBenchmarkIT -Dexchangex.benchmark.enabled=true.
 * Requires a disposable database named exactly exchangex_test, and no concurrent tests.
 */
@SpringBootTest(classes = ExchangeApiApplication.class,
        properties = {"coordinator.auto-initialize=false", "spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("postgres-integration")
@EnabledIfEnvironmentVariable(named = "EXCHANGEX_TEST_DB_URL", matches = ".*exchangex_test.*")
@EnabledIfSystemProperty(named = "exchangex.benchmark.enabled", matches = "true")
class ExchangeLoadBenchmarkIT {
    @Autowired MockMvc mvc;
    @Autowired CommandRepository commands;
    @Autowired DurableCommandCoordinator coordinator;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 100_000);

    @BeforeAll
    static void migrate(@Autowired DataSource source) throws Exception {
        try (Connection connection = source.getConnection();
             var st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_database()")) {
            assertTrue(rs.next());
            assertEquals("exchangex_test", rs.getString(1),
                    "Benchmark must run ONLY against the dedicated expendable test DB");
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void startCoordinatorAfterDatabaseReady() {
        coordinator.initialize();
    }

    record Accepted(long orderId, long sequenceId, long startedNanos, long acceptedNanos) {}
    record Batch(List<Accepted> accepted, long rejected, long failed, long startedNanos, long admissionFinishedNanos) {}
    record Completion(List<Double> latenciesMs, long lastObservedNanos) {}

    @Test
    void benchmarkOptIn() throws Exception {
        int count = Integer.getInteger("exchangex.benchmark.requests", 200);
        int workers = Integer.getInteger("exchangex.benchmark.workers", 8);
        int warmup = Integer.getInteger("exchangex.benchmark.warmup", 20);
        String scenario = System.getProperty("exchangex.benchmark.scenario", "noncrossing");
        assertTrue(count >= 1 && count <= 5_000, "requests must be between 1 and 5000");
        assertTrue(workers >= 1 && workers <= 32, "workers must be between 1 and 32");
        assertTrue(warmup >= 0 && warmup <= 1_000);
        assertTrue(Set.of("noncrossing", "crossing", "cancels").contains(scenario));

        if (warmup > 0) {
            Batch warm = sendBatch(warmup, workers, "noncrossing");
            assertEquals(warmup, warm.accepted().size(), "warm-up admissions failed");
            awaitCompletion(warm, Duration.ofSeconds(90));
        }

        Batch run = sendBatch(count, workers, scenario);
        Completion observed = awaitCompletion(run, Duration.ofSeconds(120));
        long successful = run.accepted().size();
        assertEquals(count, successful + run.rejected() + run.failed(), "all attempts must be accounted for");
        assertEquals(successful, observed.latenciesMs().size());
        List<Double> admission = run.accepted().stream()
                .map(s -> (s.acceptedNanos() - s.startedNanos()) / 1_000_000.0).toList();
        double admissionSec = (run.admissionFinishedNanos() - run.startedNanos()) / 1_000_000_000.0;
        double completionSec = (observed.lastObservedNanos() - run.startedNanos()) / 1_000_000_000.0;
        System.out.printf(Locale.ROOT,
                "BENCHMARK scenario=%s requested=%d admitted=%d rejected=%d failed=%d workers=%d warmup=%d%n",
                scenario, count, successful, run.rejected(), run.failed(), workers, warmup);
        System.out.printf(Locale.ROOT,
                "BENCHMARK mvc_admission_per_s=%.2f durable_completed_per_s=%.2f " +
                "admission_ms_p50=%.3f admission_ms_p95=%.3f admission_ms_p99=%.3f " +
                "completion_observed_ms_p50=%.3f completion_observed_ms_p95=%.3f " +
                "completion_observed_ms_p99=%.3f elapsed_s=%.3f%n",
                successful / admissionSec, successful / completionSec,
                percentile(admission, .50), percentile(admission, .95), percentile(admission, .99),
                percentile(observed.latenciesMs(), .50), percentile(observed.latenciesMs(), .95),
                percentile(observed.latenciesMs(), .99), completionSec);

        if ("cancels".equals(scenario)) {
            // Separate concurrent cancellation phase, after submissions are durable.
            // Non-crossing prices leave these orders resting and cancellable.
            List<Accepted> targets = run.accepted().stream().limit(Math.max(1, count / 10)).toList();
            long cancelStart = System.nanoTime();
            ExecutorService cancelPool = Executors.newFixedThreadPool(Math.min(workers, targets.size()));
            List<Long> cancelled = new ArrayList<>();
            try {
                List<Future<Long>> results = new ArrayList<>();
                for (Accepted target : targets) {
                    results.add(cancelPool.submit(() -> {
                        MvcResult request = mvc.perform(delete("/api/v1/orders/" + target.orderId())).andReturn();
                        MvcResult response = request.getRequest().isAsyncStarted()
                                ? mvc.perform(asyncDispatch(request)).andReturn() : request;
                        assertEquals(202, response.getResponse().getStatus(), "cancel must be admitted");
                        return target.orderId();
                    }));
                }
                for (Future<Long> result : results) cancelled.add(result.get(30, TimeUnit.SECONDS));
            } finally {
                cancelPool.shutdownNow();
            }
            // Wait until each cancellation is reflected in the authoritative order state.
            // The cancel commands themselves are handled by the same durable worker.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            for (long id : cancelled) {
                while (System.nanoTime() < deadline) {
                    // Existence of a COMPLETED cancel proves cancellation command processed.
                    Long complete = jdbcCancelCount(id);
                    if (complete > 0) break;
                    Thread.sleep(10);
                }
                assertTrue(jdbcCancelCount(id) > 0, "cancel command did not complete for " + id);
            }
            System.out.printf(Locale.ROOT,
                    "BENCHMARK cancellations_completed=%d cancels_elapsed_s=%.3f%n",
                    cancelled.size(), (System.nanoTime() - cancelStart) / 1e9);
        }
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long jdbcCancelCount(long id) {
        Long result = jdbc.queryForObject("SELECT COUNT(*) FROM exchange_commands " +
                "WHERE order_id = ? AND command_type='CANCEL' AND status='COMPLETED'", Long.class, id);
        return result == null ? 0L : result;
    }

    private Batch sendBatch(int count, int workers, String scenario) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger rejections = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Future<Accepted>> futures = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (int i = 0; i < count; i++) {
                final int position = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    long orderId = IDS.incrementAndGet();
                    boolean buy = (position & 1) == 0;
                    long price = "crossing".equals(scenario) ? 10_000 : (buy ? 9_900 : 10_100);
                    String payload = String.format(Locale.ROOT,
                            "{\"id\":%d,\"side\":\"%s\",\"price\":%d,\"quantity\":1}",
                            orderId, buy ? "BUY" : "SELL", price);
                    long begin = System.nanoTime();
                    MvcResult result = mvc.perform(post("/api/v1/orders")
                            .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn();
                    MvcResult response = result.getRequest().isAsyncStarted()
                            ? mvc.perform(asyncDispatch(result)).andReturn() : result;
                    long acceptedAt = System.nanoTime();
                    int code = response.getResponse().getStatus();
                    if (code != 202) {
                        if (code >= 400 && code < 500 || code == 503) rejections.incrementAndGet();
                        else failures.incrementAndGet();
                        return null;
                    }
                    long seq = mapper.readTree(response.getResponse().getContentAsString())
                            .path("sequenceId").asLong();
                    assertTrue(seq > 0, "no durable sequence assigned");
                    return new Accepted(orderId, seq, begin, acceptedAt);
                }));
            }
            gate.countDown();
            List<Accepted> accepted = new ArrayList<>();
            for (Future<Accepted> future : futures) {
                Accepted result = future.get(90, TimeUnit.SECONDS);
                if (result != null) accepted.add(result);
            }
            return new Batch(accepted, rejections.get(), failures.get(), started, System.nanoTime());
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
    }

    private Completion awaitCompletion(Batch batch, Duration timeout) throws Exception {
        Map<Long, Long> observed = new java.util.HashMap<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && observed.size() < batch.accepted().size()) {
            for (Accepted sample : batch.accepted()) {
                if (observed.containsKey(sample.sequenceId())) continue;
                CommandRecord command = commands.findBySequenceId(sample.sequenceId()).orElseThrow();
                if ("COMPLETED".equals(command.status())) {
                    observed.put(sample.sequenceId(), System.nanoTime());
                } else if ("FAILED".equals(command.status())) {
                    fail("Durable command FAILED sequence=" + sample.sequenceId());
                }
            }
            if (observed.size() < batch.accepted().size()) Thread.sleep(10);
        }
        assertEquals(batch.accepted().size(), observed.size(), "Not all admitted commands completed");
        List<Double> latency = batch.accepted().stream().map(s ->
                (observed.get(s.sequenceId()) - s.startedNanos()) / 1e6).toList();
        long last = observed.values().stream().mapToLong(Long::longValue).max()
                .orElse(batch.admissionFinishedNanos());
        return new Completion(latency, Math.max(last, batch.admissionFinishedNanos()));
    }

    private static double percentile(List<Double> values, double quantile) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.max(0, Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * quantile) - 1));
        return sorted.get(index);
    }
}
