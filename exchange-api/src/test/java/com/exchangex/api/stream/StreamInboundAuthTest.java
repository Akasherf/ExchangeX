package com.exchangex.api.stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamInboundAuthTest {
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JwtDecoder> decoder = mock(ObjectProvider.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final StreamInboundAuth interceptor = new StreamInboundAuth(decoder, jdbc);

    private void connect() {
        var headers = StompHeaderAccessor.create(StompCommand.CONNECT);
        headers.setSessionId("test-session");
        headers.setLeaveMutable(true);
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), channel);
    }

    private Message<byte[]> subscribe(String destination, boolean signedIn) {
        connect();
        var headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        headers.setDestination(destination);
        headers.setSessionId("test-session");
        headers.setSubscriptionId("sub-1");
        if (signedIn) headers.setUser(new UsernamePasswordAuthenticationToken("42", null));
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    @Test
    void anonymousCannotSubscribeToPrivateOrders() {
        assertThrows(SecurityException.class, () -> interceptor.preSend(
                subscribe("/user/queue/orders", false), channel));
    }

    @Test
    void authenticatedUserCanSubscribeToOnlyOwnQueue() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(42L))).thenReturn(true);
        assertNotNull(interceptor.preSend(subscribe("/user/queue/orders", true), channel));
        assertThrows(SecurityException.class, () -> interceptor.preSend(
                subscribe("/user/99/queue/orders", true), channel));
        assertThrows(SecurityException.class, () -> interceptor.preSend(
                subscribe("/queue/orders", true), channel));
    }

    @Test
    void marketRequiresKnownActiveInstrument() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("AAPL"))).thenReturn(true);
        assertNotNull(interceptor.preSend(subscribe("/topic/market/AAPL", false), channel));
        assertThrows(SecurityException.class, () -> interceptor.preSend(
                subscribe("/topic/market/UNKNOWN", false), channel));
    }

    @Test
    void clientCannotPublishToAnyBrokerDestination() {
        var headers = StompHeaderAccessor.create(StompCommand.SEND);
        headers.setDestination("/topic/market/AAPL");
        headers.setSessionId("test-session");
        headers.setLeaveMutable(true);
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        assertThrows(SecurityException.class, () -> interceptor.preSend(message, channel));
    }
}
