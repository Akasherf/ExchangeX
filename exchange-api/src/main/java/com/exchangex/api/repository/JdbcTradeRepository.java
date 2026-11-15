package com.exchangex.api.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
@Profile("!test")
public class JdbcTradeRepository implements TradeRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<TradeRecord> TRADE_MAPPER = (rs, rowNum) -> mapTrade(rs);

    public JdbcTradeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertTrades(List<TradeRecord> trades) {
        if (trades.isEmpty()) return;

        String sql = """
            INSERT INTO trades (buy_order_id, sell_order_id, execution_price, execution_quantity,
                               buy_sequence_id, sell_sequence_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        jdbcTemplate.batchUpdate(sql, trades, trades.size(),
                (ps, trade) -> {
                    ps.setLong(1, trade.buyOrderId());
                    ps.setLong(2, trade.sellOrderId());
                    ps.setLong(3, trade.executionPrice());
                    ps.setLong(4, trade.executionQuantity());
                    ps.setLong(5, trade.buySequenceId());
                    ps.setLong(6, trade.sellSequenceId());
                });
    }

    @Override
    public List<TradeRecord> findBySymbol(String symbol) {
        // We need to join with orders to filter by symbol
        String sql = """
            SELECT t.trade_id, t.buy_order_id, t.sell_order_id, t.execution_price,
                   t.execution_quantity, t.buy_sequence_id, t.sell_sequence_id
            FROM trades t
            JOIN orders o_buy ON t.buy_order_id = o_buy.order_id
            WHERE o_buy.symbol = ?
            ORDER BY t.trade_id
            """;
        return jdbcTemplate.query(sql, TRADE_MAPPER, symbol);
    }

    @Override
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM trades");
    }

    private static TradeRecord mapTrade(ResultSet rs) throws SQLException {
        return new TradeRecord(
                rs.getLong("trade_id"),
                rs.getLong("buy_order_id"),
                rs.getLong("sell_order_id"),
                rs.getLong("execution_price"),
                rs.getLong("execution_quantity"),
                rs.getLong("buy_sequence_id"),
                rs.getLong("sell_sequence_id")
        );
    }
}