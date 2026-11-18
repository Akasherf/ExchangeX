package com.exchangex.api.stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Private REST resynchronization: never trust an externally supplied userId. */
@RestController
@RequestMapping("/api/v1/account/orders")
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public class MyOrdersController {
    private final JdbcTemplate jdbc;

    public MyOrdersController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MyOrder(long id, String symbol, String side, long limitPrice,
                          long originalQuantity, long remainingQuantity, String status,
                          long sequenceId) {}

    @GetMapping
    public List<MyOrder> orders(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        final long id;
        try {
            id = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (id <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return jdbc.query(
                "SELECT o.client_order_id, o.symbol, o.side, o.limit_price, o.original_quantity, " +
                "o.remaining_quantity, o.execution_status, o.exchange_sequence_id " +
                "FROM orders o JOIN order_ownership own ON own.order_id=o.client_order_id " +
                "JOIN exchange_users u ON u.id=own.user_id AND u.enabled=TRUE " +
                "WHERE own.user_id=? ORDER BY o.exchange_sequence_id DESC LIMIT 100",
                (rs, row) -> new MyOrder(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getString(7), rs.getLong(8)), id);
    }
}
