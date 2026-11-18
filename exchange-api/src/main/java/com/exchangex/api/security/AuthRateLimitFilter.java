package com.exchangex.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded instance-local defense in depth; Nginx enforces real client-IP
 * limits at the public edge. NEVER trust X-Forwarded-For from direct API clients.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "exchangex.security.enabled", havingValue = "true", matchIfMissing = true)
public final class AuthRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_NS = 60_000_000_000L;
    private static final int MAX_KEYS = 4_096;
    private static final class Bucket {
        final long start;
        final AtomicInteger calls = new AtomicInteger();
        Bucket(long start) { this.start = start; }
    }
    private final ConcurrentHashMap<String, Bucket> counters = new ConcurrentHashMap<>();

    boolean allow(String key, int max, long now) {
        Bucket bucket = counters.get(key);
        if (bucket == null || now - bucket.start >= WINDOW_NS || now < bucket.start) {
            if (counters.size() >= MAX_KEYS) {
                counters.entrySet().removeIf(e -> now - e.getValue().start >= WINDOW_NS);
                if (counters.size() >= MAX_KEYS) return false; // fail closed, no unbounded growth
            }
            bucket = counters.compute(key, (k, old) -> old == null || now - old.start >= WINDOW_NS || now < old.start
                    ? new Bucket(now) : old);
        }
        return bucket.calls.incrementAndGet() <= max;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String route = request.getRequestURI();
        if ("POST".equals(request.getMethod()) &&
                ("/api/v1/auth/login".equals(route) || "/api/v1/auth/register".equals(route))) {
            int limit = route.endsWith("/login") ? 60 : 20;
            // remoteAddr is Nginx when proxied; use edge limits for real IPs.
            String key = route + ':' + request.getRemoteAddr();
            if (!allow(key, limit, System.nanoTime())) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"RATE_LIMITED\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
