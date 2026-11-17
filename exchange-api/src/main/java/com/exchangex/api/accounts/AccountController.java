package com.exchangex.api.accounts;

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

/** Authenticated, read-only account endpoints; user ID is never accepted from a client. */
@RestController
@RequestMapping("/api/v1/account")
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public class AccountController {
    private final AccountLedger ledger;
    private final JdbcTemplate jdbc;

    public AccountController(AccountLedger ledger, JdbcTemplate jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    public record CashResponse(long available, long reserved, long total) {}
    public record PositionResponse(String symbol, long available, long reserved, long total) {}

    @GetMapping
    public CashResponse cash(@AuthenticationPrincipal Jwt principal) {
        var balance = ledger.cashBalance(validUser(principal));
        return new CashResponse(balance.available(), balance.reserved(), balance.total());
    }

    @GetMapping("/holdings")
    public List<PositionResponse> holdings(@AuthenticationPrincipal Jwt principal) {
        return ledger.holdings(validUser(principal)).stream()
                .map(p -> new PositionResponse(p.symbol(), p.available(), p.reserved(), p.total()))
                .toList();
    }

    private long validUser(Jwt principal) {
        if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            long userId = Long.parseLong(principal.getSubject());
            if (userId > 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM exchange_users WHERE id = ? AND enabled = TRUE)",
                    Boolean.class, userId))) return userId;
        } catch (NumberFormatException ignored) {
            // Invalid token subject.
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
