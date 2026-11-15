package com.exchangex.api.repository;

import com.exchangex.engine.Side;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!test")
public class JdbcOrderRepository implements OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<OrderRecord> ORDER_MAPPER = (rs, rowNum) -> mapOrder(rs);

    public JdbcOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void insertOrder(OrderRecord order) {
        String sql = """
            INSERT INTO orders (order_id, client_order_id, exchange_sequence_id, symbol, side, limit_price,
                               original_quantity, remaining_quantity, execution_status, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
                order.orderId(),
                order.clientOrderId(),
                order.exchangeSequenceId(),
                order.symbol(),
                order.side().name(),
                order.limitPrice(),
                order.originalQuantity(),
                order.remainingQuantity(),
                order.executionStatus(),
                order.status()
        );
    }

    @Override
    public void updateOrderExecution(long clientOrderId, long remainingQuantity, String executionStatus) {
        String status = switch (executionStatus) {
            case "FILLED" -> "FILLED";
            case "PARTIAL" -> "PARTIALLY_FILLED";
            case "CANCELLED" -> "CANCELLED";
            default -> "OPEN";
        };
        String sql = """
            UPDATE orders
            SET remaining_quantity = ?, execution_status = ?, status = ?
            WHERE client_order_id = ?
            """;
        jdbcTemplate.update(sql, remainingQuantity, executionStatus, status, clientOrderId);
    }

    @Override
    public void markCancelled(long clientOrderId) {
        String sql = """
            UPDATE orders
            SET remaining_quantity = 0, execution_status = 'CANCELLED', status = 'CANCELLED'
            WHERE client_order_id = ?
            """;
        jdbcTemplate.update(sql, clientOrderId);
    }

    @Override
    public Optional<OrderRecord> findByClientOrderId(long clientOrderId) {
        String sql = """
            SELECT order_id, client_order_id, exchange_sequence_id, symbol, side, limit_price,
                   original_quantity, remaining_quantity, execution_status, status
            FROM orders
            WHERE client_order_id = ?
            """;
        List<OrderRecord> results = jdbcTemplate.query(sql, ORDER_MAPPER, clientOrderId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<OrderRecord> findByExchangeSequenceId(long sequenceId) {
        String sql = """
            SELECT order_id, client_order_id, exchange_sequence_id, symbol, side, limit_price,
                   original_quantity, remaining_quantity, execution_status, status
            FROM orders
            WHERE exchange_sequence_id = ?
            """;
        List<OrderRecord> results = jdbcTemplate.query(sql, ORDER_MAPPER, sequenceId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public List<OrderRecord> findActiveOrders(String symbol) {
        String sql = """
            SELECT order_id, client_order_id, exchange_sequence_id, symbol, side, limit_price,
                   original_quantity, remaining_quantity, execution_status, status
            FROM orders
            WHERE symbol = ? AND execution_status IN ('PENDING', 'PARTIAL')
            ORDER BY exchange_sequence_id
            """;
        return jdbcTemplate.query(sql, ORDER_MAPPER, symbol);
    }

    @Override
    public void deleteAll() {
        jdbcTemplate.update("DELETE FROM orders");
    }

    private static OrderRecord mapOrder(ResultSet rs) throws SQLException {
        return new OrderRecord(
                rs.getLong("order_id"),
                rs.getLong("client_order_id"),
                rs.getLong("exchange_sequence_id"),
                rs.getString("symbol"),
                Side.valueOf(rs.getString("side")),
                rs.getLong("limit_price"),
                rs.getLong("original_quantity"),
                rs.getLong("remaining_quantity"),
                rs.getString("execution_status"),
                rs.getString("status")
        );
    }
}