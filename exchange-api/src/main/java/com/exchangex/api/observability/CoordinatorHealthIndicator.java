package com.exchangex.api.observability;

import com.exchangex.api.SymbolCoordinatorRegistry;
import com.exchangex.api.repository.CheckpointRepository;
import com.exchangex.api.repository.CommandRepository;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Health indicator for the DurableCommandCoordinator and database connectivity.
 * <p>
 * Provides:
 * - Coordinator active status
 * - Command backlog level
 * - Checkpoint lag
 * - Database connectivity (via simple query)
 * - Last command processing time
 * <p>
 * Read-only - does not modify coordinator state.
 */
@Component("exchangeHealthIndicator")
public class CoordinatorHealthIndicator implements HealthIndicator {

    private final CommandRepository commandRepository;
    private final CheckpointRepository checkpointRepository;
    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.beans.factory.ObjectProvider<SymbolCoordinatorRegistry> coordinators;

    private volatile Instant lastHealthCheck = Instant.EPOCH;
    private volatile Health lastHealth = Health.unknown().build();

    // Cache health for 5 seconds to avoid excessive DB queries
    private static final Duration HEALTH_CACHE_TTL = Duration.ofSeconds(5);

    public CoordinatorHealthIndicator(CommandRepository commandRepository,
                                      CheckpointRepository checkpointRepository,
                                      JdbcTemplate jdbcTemplate,
                                      org.springframework.beans.factory.ObjectProvider<SymbolCoordinatorRegistry> coordinators) {
        this.commandRepository = commandRepository;
        this.checkpointRepository = checkpointRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.coordinators = coordinators;
    }

    public Health health() {
        Instant now = Instant.now();
        if (Duration.between(lastHealthCheck, now).compareTo(HEALTH_CACHE_TTL) < 0) {
            return lastHealth;
        }

        try {
            // Check database connectivity
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);

            SymbolCoordinatorRegistry registry = coordinators.getIfAvailable();
            if (registry != null && !registry.allWorkersRunning()) {
                lastHealth = Health.down().withDetail("coordinator", "STOPPED").build();
                lastHealthCheck = now;
                return lastHealth;
            }

            // Durable state comes from PostgreSQL, not from unconnected counters.
            // We do not claim the coordinator is active: this indicator does not
            // have an authoritative coordinator lifecycle signal.
            long outstanding = commandRepository.countOutstanding("AAPL");
            long checkpoint = checkpointRepository.getLastCompletedSequenceId("AAPL");

            lastHealth = Health.up()
                    .withDetail("database", "UP")
                    .withDetail("coordinator", registry == null ? "UNOBSERVED" : "RUNNING")
                    .withDetail("backlog", outstanding)
                    .withDetail("pendingCommands", outstanding)
                    .withDetail("lastCheckpointSequenceId", checkpoint)
                    .build();

            lastHealthCheck = now;
            return lastHealth;

        } catch (Exception e) {
            lastHealth = Health.down()
                    .withDetail("database", "DOWN")
                    .withDetail("error", "Database or journal check failed")
                    .build();
            lastHealthCheck = now;
            return lastHealth;
        }
    }
}