package com.exchangex.api.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreamDestinationPolicyTest {
    @Test
    void marketTopicsAreExactAndSymbolBounded() {
        assertTrue(StreamDestinationPolicy.isPublicMarket("/topic/market/AAPL"));
        assertTrue(StreamDestinationPolicy.isPublicMarket("/topic/market/MSFT"));
        assertFalse(StreamDestinationPolicy.isPublicMarket("/topic/market/AAPL/orders"));
        assertFalse(StreamDestinationPolicy.isPublicMarket("/topic/market/aapl"));
        assertFalse(StreamDestinationPolicy.isPublicMarket("/topic/admin"));
        assertFalse(StreamDestinationPolicy.isPublicMarket(null));
    }

    @Test
    void onlyUserDestinationIsEligibleForPrivateOrders() {
        assertTrue(StreamDestinationPolicy.isPrivateOrders("/user/queue/orders"));
        assertFalse(StreamDestinationPolicy.isPrivateOrders("/queue/orders"));
        assertFalse(StreamDestinationPolicy.isPrivateOrders("/user/other/queue/orders"));
        assertFalse(StreamDestinationPolicy.isPrivateOrders(null));
    }

    @Test
    void publicPayloadCannotExposeOrderIdentity() {
        var names = java.util.Arrays.stream(MarketStreamService.MarketSnapshot.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(names.contains("orderId"));
        assertFalse(names.contains("userId"));
        assertFalse(names.contains("clientOrderId"));
    }
}
