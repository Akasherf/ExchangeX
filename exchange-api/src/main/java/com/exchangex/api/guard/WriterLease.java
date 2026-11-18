package com.exchangex.api.guard;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * An exclusive, process-wide PostgreSQL session lock PLUS a monotonically
 * increasing fencing generation. Every transaction changing the command
 * journal or its committed projections locks/checks the generation row.
 *
 * The lease is deliberately held on a DEDICATED, borrowed JDBC connection,
 * never a pooled connection temporarily borrowed by JdbcTemplate.
 */
@Component
public final class WriterLease implements AutoCloseable {
    private static final int LOCK_NAMESPACE = 736719;
    private int lockId;

    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final Environment environment;
    private Connection lockConnection;
    private volatile long generation = -1;
    private volatile boolean active;

    public WriterLease(DataSource source, JdbcTemplate jdbc,
                       @Value("${exchangex.writer.guard.enabled:true}") boolean enabled,
                       Environment environment) {
        this.source = source;
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.environment = environment;
    }

    @PostConstruct
    public synchronized void start() {
        if (!enabled) {
            if (!environment.acceptsProfiles(Profiles.of("test | postgres-integration"))) {
                throw new IllegalStateException("Writer fencing cannot be disabled outside test profiles");
            }
            return;
        }
        if (active) return;
        try {
            Connection connection = source.getConnection();
            // PostgreSQL advisory locks are cluster-scoped. The database OID
            // ensures independent ExchangeX databases can run side by side.
            try (PreparedStatement oidQuery = connection.prepareStatement(
                    "SELECT oid::integer FROM pg_database WHERE datname=current_database()");
                 ResultSet oidRow = oidQuery.executeQuery()) {
                if (!oidRow.next()) throw new IllegalStateException("Current database OID not found");
                lockId = oidRow.getInt(1);
            }
            boolean acquired;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_try_advisory_lock(?, ?)")) {
                statement.setInt(1, LOCK_NAMESPACE);
                statement.setInt(2, lockId);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    acquired = rows.getBoolean(1);
                }
            }
            if (!acquired) {
                connection.close();
                throw new IllegalStateException("Another ExchangeX writer already owns this PostgreSQL database");
            }
            lockConnection = connection;
            // An old process can still be finishing a transaction after losing
            // its session. The row UPDATE waits for that transaction and moves
            // the generation forward BEFORE this process replays the journal.
            Long next = jdbc.queryForObject(
                    "UPDATE exchange_writer_fence SET generation=generation+1, acquired_at=now() " +
                    "WHERE id=1 RETURNING generation", Long.class);
            if (next == null) throw new IllegalStateException("Writer fence row is missing");
            generation = next;
            active = true;
        } catch (SQLException | RuntimeException e) {
            close();
            throw new IllegalStateException("Unable to acquire exclusive ExchangeX writer lease", e);
        }
    }

    /** Called *inside* admission and commit transactions, before mutations. */
    public void assertFencedTransaction() {
        if (!enabled) return;
        if (!healthy()) throw new IllegalStateException("ExchangeX writer lease was lost");
        // FOR UPDATE serializes competing generation changes with our commit.
        Long actual = jdbc.queryForObject(
                "SELECT generation FROM exchange_writer_fence WHERE id=1 FOR UPDATE", Long.class);
        if (actual == null || actual != generation) {
            throw new IllegalStateException("Stale ExchangeX writer generation; refusing to commit");
        }
    }

    public synchronized boolean healthy() {
        if (!enabled) return true;
        if (!active || lockConnection == null) return false;
        try {
            return lockConnection.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        active = false;
        generation = -1;
        if (lockConnection == null) return;
        try {
            if (!lockConnection.isClosed()) {
                try (PreparedStatement statement = lockConnection.prepareStatement(
                        "SELECT pg_advisory_unlock(?, ?)")) {
                    statement.setInt(1, LOCK_NAMESPACE);
                    statement.setInt(2, lockId);
                    statement.execute();
                } catch (SQLException ignored) {
                    // Session close releases the lock even if unlock fails.
                }
            }
        } catch (SQLException ignored) {
            // Connection may already be broken.
        } finally {
            try { lockConnection.close(); } catch (SQLException ignored) { }
            lockConnection = null;
        }
    }
}
