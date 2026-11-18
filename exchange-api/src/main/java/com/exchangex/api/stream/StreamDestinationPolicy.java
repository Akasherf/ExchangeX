package com.exchangex.api.stream;

/** Whitelist of inbound destinations; outbound broker destinations are server-owned. */
public final class StreamDestinationPolicy {
    private StreamDestinationPolicy() {}

    public static boolean isPublicMarket(String destination) {
        return destination != null && destination.matches("/topic/market/[A-Z][A-Z0-9]{0,9}");
    }

    public static boolean isPrivateOrders(String destination) {
        return "/user/queue/orders".equals(destination);
    }
}
