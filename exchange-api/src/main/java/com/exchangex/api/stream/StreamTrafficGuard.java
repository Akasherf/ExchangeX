package com.exchangex.api.stream;


import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded per-JVM STOMP quotas. Nginx separately limits WebSocket handshakes. */
public final class StreamTrafficGuard {
    private static final int MAX_SESSIONS = 200;
    private static final int MAX_SUBSCRIPTIONS = 8;
    private static final int MAX_FRAMES_PER_MINUTE = 120;
    private static final long WINDOW_NS = 60_000_000_000L;
    private static final class State {
        final Set<String> subscriptionIds = new HashSet<>();
        long windowStarted = System.nanoTime();
        int frames;
    }
    private final ConcurrentHashMap<String, State> sessions = new ConcurrentHashMap<>();

    public synchronized void onConnect(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new SecurityException("Missing STOMP session");
        if (sessions.size() >= MAX_SESSIONS && !sessions.containsKey(sessionId))
            throw new SecurityException("Too many WebSocket sessions");
        sessions.computeIfAbsent(sessionId, id -> new State());
    }

    public synchronized void onDisconnect(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    public void onFrame(String sessionId, int payloadSize) {
        if (payloadSize > 8_192) throw new SecurityException("STOMP frame payload exceeds limit");
        if (sessionId == null) throw new SecurityException("Missing STOMP session");
        State state = sessions.get(sessionId);
        if (state == null) throw new SecurityException("STOMP session not connected");
        synchronized (state) {
            long now = System.nanoTime();
            if (now < state.windowStarted || now - state.windowStarted >= WINDOW_NS) {
                state.windowStarted = now;
                state.frames = 0;
            }
            if (++state.frames > MAX_FRAMES_PER_MINUTE)
                throw new SecurityException("STOMP message rate exceeded");
        }
    }

    public void onSubscribe(String sessionId, String subscriptionId) {
        State state = sessions.get(sessionId);
        if (state == null) throw new SecurityException("STOMP session not connected");
        if (subscriptionId == null || subscriptionId.isBlank() || subscriptionId.length() > 128)
            throw new SecurityException("STOMP subscription ID required");
        synchronized (state) {
            if (state.subscriptionIds.contains(subscriptionId))
                throw new SecurityException("Duplicate STOMP subscription ID");
            if (state.subscriptionIds.size() >= MAX_SUBSCRIPTIONS)
                throw new SecurityException("Too many STOMP subscriptions");
            state.subscriptionIds.add(subscriptionId);
        }
    }

    public void onUnsubscribe(String sessionId, String subscriptionId) {
        State state = sessions.get(sessionId);
        if (state != null) synchronized (state) { state.subscriptionIds.remove(subscriptionId); }
    }

    public int activeSessions() { return sessions.size(); }
}
