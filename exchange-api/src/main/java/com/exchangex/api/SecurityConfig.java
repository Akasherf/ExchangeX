package com.exchangex.api;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${exchangex.security.enabled:true}") boolean enabled,
            Environment environment) throws Exception {
        // The disabled mode exists exclusively for existing isolated integration tests.
        if (!enabled && !environment.acceptsProfiles(Profiles.of("test | postgres-integration"))) {
            throw new IllegalStateException("Disabling ExchangeX security outside test profiles is forbidden");
        }
        http.csrf(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (enabled) {
            http.authorizeHttpRequests(a -> a
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                    // The WebSocket handshake may be anonymous; STOMP subscriptions are
                    // authorized separately on the inbound messaging channel.
                    .requestMatchers(HttpMethod.GET, "/ws", "/ws/**", "/api/v1/market/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/orderbook/**", "/api/v1/trades",
                            "/api/v1/instruments", "/api/v1/instruments/*/trades",
                            "/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                    .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
        } else {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        }
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
    SecretKey jwtSigningKey(@Value("${EXCHANGEX_JWT_SECRET_BASE64:}") String base64Secret) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("EXCHANGEX_JWT_SECRET_BASE64 is not valid Base64", e);
        }
        if (secret.length < 32) {
            throw new IllegalStateException("EXCHANGEX_JWT_SECRET_BASE64 must contain >=32 random bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    @ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    @ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey,
            @Value("${exchangex.security.jwt.issuer:exchangex}") String issuer,
            @Value("${exchangex.security.jwt.audience:exchangex-api}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> validAudience = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong token audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), validAudience));
        return decoder;
    }
}
