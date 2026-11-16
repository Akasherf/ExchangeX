package com.exchangex.api.repository;

import com.exchangex.engine.Side;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!test")
public class JdbcCommandRepository implements CommandRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<CommandRecord> COMMAND_MAPPER = (rs, rowNum) -> mapCommand(rs);

    public JdbcCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long insertSubmitCommand(long clientOrderId, String symbol, Side side, long price, long quantity) {
        String sql = """
            INSERT INTO exchange_commands (command_type, order_id, symbol, side, price, quantity, status)
            VALUES ('SUBMIT', ?, ?, ?, ?, ?, 'PENDING')
            RETURNING sequence_id
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, clientOrderId);
            ps.setString(2, symbol);
            ps.setString(3, side.name());
            ps.setLong(4, price);
            ps.setLong(5, quantity);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public long insertCancelCommand(long clientOrderId, String symbol) {
        String sql = """
            INSERT INTO exchange_commands (command_type, order_id, symbol, status)
            VALUES ('CANCEL', ?, ?, 'PENDING')
            RETURNING sequence_id
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, clientOrderId);
            ps.setString(2, symbol);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<CommandRecord> findPendingSubmitByClientOrderId(long clientOrderId, String symbol) {
        String sql = """
            SELECT sequence_id, command_type, order_id, symbol, side, price, quantity, status
            FROM exchange_commands
            WHERE order_id = ? AND symbol = ? AND command_type = 'SUBMIT'
            """;
        List<CommandRecord> results = jdbcTemplate.query(sql, COMMAND_MAPPER, clientOrderId, symbol);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<CommandRecord> findPendingCancelByClientOrderId(long clientOrderId, String symbol) {
        String sql = """
            SELECT sequence_id, command_type, order_id, symbol, side, price, quantity, status
            FROM exchange_commands
            WHERE order_id = ? AND symbol = ? AND command_type = 'CANCEL' AND status IN ('PENDING', 'FAILED')
            """;
        List<CommandRecord> results = jdbcTemplate.query(sql, COMMAND_MAPPER, clientOrderId, symbol);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<CommandRecord> findPendingCommandsSince(String symbol, long lastCompletedSequenceId) {
        String sql = """
            SELECT sequence_id, command_type, order_id, symbol, side, price, quantity, status
            FROM exchange_commands
            WHERE symbol = ? AND sequence_id > ? AND status IN ('PENDING', 'FAILED')
            ORDER BY sequence_id
            """;
        return jdbcTemplate.query(sql, COMMAND_MAPPER, symbol, lastCompletedSequenceId);
    }

    @Override
    public long countOutstanding(String symbol) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exchange_commands WHERE symbol = ? AND status IN ('PENDING', 'FAILED')",
                Long.class, symbol);
        return count == null ? 0L : count;
    }

    @Override
    public void markCompleted(long sequenceId) {
        String sql = """
            UPDATE exchange_commands
            SET status = 'COMPLETED', completed_at = NOW()
            WHERE sequence_id = ?
            """;
        jdbcTemplate.update(sql, sequenceId);
    }

    @Override
    public void markFailed(long sequenceId) {
        String sql = """
            UPDATE exchange_commands
            SET status = 'FAILED', completed_at = NOW()
            WHERE sequence_id = ?
            """;
        jdbcTemplate.update(sql, sequenceId);
    }

    @Override
    public Optional<CommandRecord> findBySequenceId(long sequenceId) {
        String sql = """
            SELECT sequence_id, command_type, order_id, symbol, side, price, quantity, status
            FROM exchange_commands
            WHERE sequence_id = ?
            """;
        List<CommandRecord> results = jdbcTemplate.query(sql, COMMAND_MAPPER, sequenceId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM exchange_commands");
    }

    private static CommandRecord mapCommand(ResultSet rs) throws SQLException {
        String commandType = rs.getString("command_type");
        Side side = commandType.equals("SUBMIT") ? Side.valueOf(rs.getString("side")) : null;
        Long price = commandType.equals("SUBMIT") ? rs.getLong("price") : null;
        Long quantity = commandType.equals("SUBMIT") ? rs.getLong("quantity") : null;
        String status = rs.getString("status");
        return new CommandRecord(
                rs.getLong("sequence_id"),
                commandType,
                rs.getLong("order_id"),
                rs.getString("symbol"),
                side,
                price,
                quantity,
                status
        );
    }
}