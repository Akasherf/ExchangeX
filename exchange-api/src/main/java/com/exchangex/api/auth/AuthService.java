package com.exchangex.api.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.exchangex.api.accounts.AccountLedger;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public class AuthService {
    private final ExchangeUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final AccountLedger accounts;

    public AuthService(ExchangeUserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens,
            AccountLedger accounts) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.accounts = accounts;
    }

    @Transactional
    public long register(String email, String password) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        }
        try {
            long id = users.create(normalized, passwordEncoder.encode(password));
            accounts.openAccount(id);
            return id;
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }
    }

    public TokenResponse login(String email, String password) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        var user = users.findByEmail(normalized).orElse(null);
        if (user == null || !user.enabled() || !passwordEncoder.matches(password, user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return new TokenResponse(tokens.issue(user.id()), "Bearer", tokens.ttlSeconds());
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}
}
