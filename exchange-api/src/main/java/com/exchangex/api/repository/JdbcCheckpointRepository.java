package com.exchangex.api.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcCheckpointRepository implements CheckpointRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcCheckpointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long getLastCompletedSequenceId(String symbol) {
        String sql = "SELECT last_completed_sequence_id FROM command_checkpoint WHERE symbol = ?";
        Long result = jdbcTemplate.query(sql, rs -> rs.next() ? rs.getLong(1) : null, symbol);
        return result != null ? result : 0L;
    }

    @Override
    public boolean advanceIfGreater(String symbol, long newSequenceId) {
        String sql = """
            UPDATE command_checkpoint
            SET last_completed_sequence_id = ?, updated_at = NOW()
            WHERE symbol = ? AND last_completed_sequence_id < ?
            """;
        int updated = jdbcTemplate.update(sql, newSequenceId, symbol, newSequenceId);
        return updated > 0;
    }

    @Override
    public void reset() {
        jdbcTemplate.update("UPDATE command_checkpoint SET last_completed_sequence_id = 0");
    }
}