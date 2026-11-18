package com.exchangex.api.stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;

/**
 * CONNECT uses a STOMP Authorization header, never a URL token. Anonymous clients
 * may subscribe to market topics only. SEND and unexpected subscriptions are denied.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "exchangex.stream.enabled", havingValue = "true", matchIfMissing = true)
public class StreamInboundAuth implements ChannelInterceptor {
    private final ObjectProvider<JwtDecoder> decoder;
    private final JdbcTemplate jdbc;

    public StreamInboundAuth(ObjectProvider<JwtDecoder> decoder, JdbcTemplate jdbc) {
        this.decoder = decoder;
        this.jdbc = jdbc;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor headers = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (headers == null || headers.getCommand() == null) return message;
        StompCommand command = headers.getCommand();
        if (command == StompCommand.CONNECT) {
            String bearer = headers.getFirstNativeHeader("Authorization");
            if (bearer != null) {
                if (!bearer.startsWith("Bearer ") || bearer.length() <= 7) {
                    throw new SecurityException("Invalid STOMP Authorization header");
                }
                JwtDecoder verifier = decoder.getIfAvailable();
                if (verifier == null) throw new SecurityException("JWT login not enabled");
                try {
                    Jwt jwt = verifier.decode(bearer.substring(7));
                    long userId = Long.parseLong(jwt.getSubject());
                    if (userId <= 0 || !enabledUser(userId)) throw new SecurityException("Account disabled");
                    headers.setUser(new UsernamePasswordAuthenticationToken(
                            String.valueOf(userId), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                } catch (JwtException | NumberFormatException e) {
                    throw new SecurityException("Invalid or expired token", e);
                }
            }
            return message;
        }
        if (command == StompCommand.SUBSCRIBE) {
            String destination = headers.getDestination();
            if (StreamDestinationPolicy.isPublicMarket(destination)) {
                String symbol = destination.substring("/topic/market/".length());
                if (Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM instruments WHERE symbol=? AND active=TRUE)",
                        Boolean.class, symbol))) return message;
            }
            if (StreamDestinationPolicy.isPrivateOrders(destination) && authenticated(headers.getUser())) {
                return message;
            }
            throw new SecurityException("Subscription not authorized");
        }
        if (command == StompCommand.UNSUBSCRIBE || command == StompCommand.DISCONNECT) return message;
        // No client publishing to /topic, /queue or /app. REST performs all trading actions.
        throw new SecurityException("Client STOMP command not allowed");
    }

    private boolean authenticated(Principal principal) {
        if (principal == null) return false;
        try {
            return enabledUser(Long.parseLong(principal.getName()));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean enabledUser(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM exchange_users WHERE id=? AND enabled=TRUE)",
                Boolean.class, userId));
    }
}
