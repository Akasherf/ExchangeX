package com.exchangex.api.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public class ExchangeUserRepository {
    private final JdbcTemplate jdbc;

    public ExchangeUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record User(long id, String email, String passwordHash, boolean enabled) {}

    public long create(String email, String hash) {
        Long id = jdbc.queryForObject(
                "INSERT INTO exchange_users (email, password_hash) VALUES (?, ?) RETURNING id",
                Long.class, email, hash);
        if (id == null) throw new IllegalStateException("Could not create user");
        return id;
    }

    public Optional<User> findByEmail(String email) {
        List<User> users = jdbc.query(
                "SELECT id, email, password_hash, enabled FROM exchange_users WHERE email=?",
                (rs, n) -> new User(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), email);
        return users.stream().findFirst();
    }
}
