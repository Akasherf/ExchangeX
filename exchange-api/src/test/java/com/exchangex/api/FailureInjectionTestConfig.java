package com.exchangex.api;

import com.exchangex.api.repository.CommandRepository;
import com.exchangex.api.repository.OrderRepository;
import com.exchangex.api.repository.TradeRepository;
import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.engine.MatchingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test configuration providing a configurable FailureInjection implementation
 * for deterministic fault injection tests.
 * <p>
 * This config is ONLY imported by FaultInjectionIntegrationTest via @Import.
 * It does NOT use @Profile so it won't interfere with other integration tests.
 * Package-private to avoid component scanning.
 */
@TestConfiguration
class FailureInjectionTestConfig {

    private static final Logger log = LoggerFactory.getLogger(FailureInjectionTestConfig.class);

    /**
     * Control bean for tests to configure failure injection behavior.
     * Thread-safe for single-threaded coordinator execution.
     */
    @Bean
    @Primary
    FailureInjectionControl failureInjectionControl() {
        return new FailureInjectionControl();
    }

    /**
     * FailureInjection implementation that delegates to the control bean.
     * Only active when control.enable() has been called.
     */
    @Bean
    @Primary
    FailureInjection failureInjection(FailureInjectionControl control) {
        return new FailureInjection() {
            @Override
            public void afterOrdersBeforeTrades(long sequenceId) {
                log.debug("FailureInjection: afterOrdersBeforeTrades called for sequenceId={}", sequenceId);
                control.triggerIfEnabled(sequenceId, "afterOrdersBeforeTrades");
            }

            @Override
            public void afterTradesBeforeCommandStatus(long sequenceId) {
                log.debug("FailureInjection: afterTradesBeforeCommandStatus called for sequenceId={}", sequenceId);
                control.triggerIfEnabled(sequenceId, "afterTradesBeforeCommandStatus");
            }

            @Override
            public void afterCommandStatusBeforeCheckpoint(long sequenceId) {
                log.debug("FailureInjection: afterCommandStatusBeforeCheckpoint called for sequenceId={}", sequenceId);
                control.triggerIfEnabled(sequenceId, "afterCommandStatusBeforeCheckpoint");
            }
        };
    }

    /**
     * Override the production coordinator with one that has failure injection enabled.
     * Uses the package-private constructor.
     */
    @Bean
    @Primary
    DurableCommandCoordinator durableCommandCoordinator(
            MatchingEngine matchingEngine,
            CommandRepository commandRepository,
            OrderRepository orderRepository,
            TradeRepository tradeRepository,
            CheckpointRepository checkpointRepository,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            FailureInjection failureInjection
    ) {
        return new DurableCommandCoordinator(
                matchingEngine,
                commandRepository,
                orderRepository,
                tradeRepository,
                checkpointRepository,
                jdbcTemplate,
                transactionTemplate,
                failureInjection
        );
    }

    /**
     * Control interface for tests to enable/disable and configure failures.
     */
    public static class FailureInjectionControl {
        private final AtomicReference<FailureConfig> config = new AtomicReference<>(FailureConfig.disabled());

        /**
         * Enable failure injection at a specific point for a specific sequence ID.
         * @param sequenceId the command sequence ID to fail on, or -1 for any
         * @param injectionPoint one of "afterOrdersBeforeTrades", "afterTradesBeforeCommandStatus", "afterCommandStatusBeforeCheckpoint"
         * @param exceptionClass the exception to throw
         */
        public void enable(long sequenceId, String injectionPoint, Class<? extends RuntimeException> exceptionClass) {
            config.set(new FailureConfig(true, sequenceId, injectionPoint, exceptionClass));
        }

        /**
         * Disable failure injection.
         */
        public void disable() {
            config.set(FailureConfig.disabled());
        }

        /**
         * Called by the FailureInjection implementation.
         */
        void triggerIfEnabled(long sequenceId, String injectionPoint) {
            FailureConfig current = config.get();
            log.debug("triggerIfEnabled called: sequenceId={}, injectionPoint={}, current={}", sequenceId, injectionPoint, current);
            if (current.enabled && current.injectionPoint.equals(injectionPoint)) {
                if (current.targetSequenceId == -1 || current.targetSequenceId == sequenceId) {
                    log.debug("TRIGGERING FAILURE for sequenceId={}, injectionPoint={}", sequenceId, injectionPoint);
                    try {
                        throw current.exceptionClass.getDeclaredConstructor(String.class)
                                .newInstance("Injected failure at " + injectionPoint + " for sequence " + sequenceId);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Failed to instantiate injected exception", e);
                    }
                }
            }
        }

        private static class FailureConfig {
            final boolean enabled;
            final long targetSequenceId;
            final String injectionPoint;
            final Class<? extends RuntimeException> exceptionClass;

            FailureConfig(boolean enabled, long targetSequenceId, String injectionPoint, Class<? extends RuntimeException> exceptionClass) {
                this.enabled = enabled;
                this.targetSequenceId = targetSequenceId;
                this.injectionPoint = injectionPoint;
                this.exceptionClass = exceptionClass;
            }

            static FailureConfig disabled() {
                return new FailureConfig(false, -1, null, null);
            }
        }
    }
}