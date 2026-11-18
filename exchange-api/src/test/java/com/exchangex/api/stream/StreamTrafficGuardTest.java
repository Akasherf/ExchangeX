package com.exchangex.api.stream;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamTrafficGuardTest {
    @Test
    void capsConcurrentSessionsAndReleasesOnDisconnect() {
        var guard = new StreamTrafficGuard();
        for (int i = 0; i < 200; i++) guard.onConnect("session-" + i);
        assertEquals(200, guard.activeSessions());
        assertThrows(SecurityException.class, () -> guard.onConnect("overflow"));
        guard.onDisconnect("session-0");
        guard.onConnect("overflow");
        assertEquals(200, guard.activeSessions());
    }

    @Test
    void capsSubscriptionsButAllowsReusingAfterUnsubscribe() {
        var guard = new StreamTrafficGuard();
        guard.onConnect("a");
        for (int i = 0; i < 8; i++) guard.onSubscribe("a", "id-" + i);
        assertThrows(SecurityException.class, () -> guard.onSubscribe("a", "too-many"));
        assertThrows(SecurityException.class, () -> guard.onSubscribe("a", "id-0"));
        guard.onUnsubscribe("a", "id-0");
        guard.onSubscribe("a", "new");
    }

    @Test
    void enforcesFrameAndPayloadBounds() {
        var guard = new StreamTrafficGuard();
        guard.onConnect("a");
        for (int i = 0; i < 120; i++) guard.onFrame("a", 0);
        assertThrows(SecurityException.class, () -> guard.onFrame("a", 0));
        assertThrows(SecurityException.class, () -> guard.onFrame("a", 8193));
        assertThrows(SecurityException.class, () -> guard.onFrame("unknown", 0));
    }
}
