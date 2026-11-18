package com.exchangex.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class AuthRateLimitFilterTest {
    @Test
    void rejectsExcessiveHttpLoginRequestsWith429() throws Exception {
        var filter = new AuthRateLimitFilter();
        for (int i = 0; i < 61; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
            request.setRemoteAddr("192.0.2.4");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { });
            if (i < 60) assertEquals(200, response.getStatus());
            else {
                assertEquals(429, response.getStatus());
                assertEquals("60", response.getHeader("Retry-After"));
            }
        }
    }

    @Test
    void permitsLimitAndRejectsExcess() {
        var limiter = new AuthRateLimitFilter();
        for (int i = 0; i < 20; i++) assertTrue(limiter.allow("register:127.0.0.1", 20, 1L));
        assertFalse(limiter.allow("register:127.0.0.1", 20, 1L));
        assertTrue(limiter.allow("register:127.0.0.2", 20, 1L));
    }

    @Test
    void expiredWindowResetsWithoutAffectingOtherKeys() {
        var limiter = new AuthRateLimitFilter();
        assertTrue(limiter.allow("login:a", 1, 100L));
        assertFalse(limiter.allow("login:a", 1, 100L));
        assertTrue(limiter.allow("login:a", 1, 60_000_000_101L));
        assertTrue(limiter.allow("login:b", 1, 100L));
    }
}
