package com.exchangex.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final long ttlSeconds;

    public JwtTokenService(JwtEncoder encoder,
            @Value("${exchangex.security.jwt.issuer:exchangex}") String issuer,
            @Value("${exchangex.security.jwt.audience:exchangex-api}") String audience,
            @Value("${exchangex.security.jwt.ttl-seconds:900}") long ttlSeconds) {
        if (ttlSeconds < 60 || ttlSeconds > 3600) throw new IllegalArgumentException("JWT TTL must be 60..3600s");
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
        this.ttlSeconds = ttlSeconds;
    }

    public String issue(long userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer).audience(List.of(audience)).subject(Long.toString(userId))
                .issuedAt(now).expiresAt(now.plusSeconds(ttlSeconds))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() { return ttlSeconds; }
}
