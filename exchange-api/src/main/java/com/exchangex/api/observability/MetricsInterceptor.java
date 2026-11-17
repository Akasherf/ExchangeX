package com.exchangex.api.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;

/**
 * Interceptor for recording API request metrics.
 * <p>
 * Captures request latency, counts, and error rates without modifying
 * controller logic. Works with Micrometer timers and counters.
 */
@Component
public class MetricsInterceptor implements HandlerInterceptor {

    private final ExchangeMetrics metrics;

    public MetricsInterceptor(ExchangeMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Store start time in request attribute
        request.setAttribute("metricsStartTime", Instant.now());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Instant startTime = (Instant) request.getAttribute("metricsStartTime");
        if (startTime != null) {
            Duration duration = Duration.between(startTime, Instant.now());
            String endpoint = request.getRequestURI();
            int statusCode = response.getStatus();
            metrics.recordApiRequest(endpoint, statusCode, duration);
        }
    }
}