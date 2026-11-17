package com.exchangex.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that extracts or generates correlation IDs for request tracing.
 * <p>
 * Adds traceId and spanId to MDC for structured logging.
 * Safe headers only - never logs sensitive data.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Extract or generate trace ID
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader(CORRELATION_ID_HEADER);
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        // Extract or generate span ID
        String spanId = request.getHeader(SPAN_ID_HEADER);
        if (spanId == null || spanId.isBlank()) {
            spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        // Only accept bounded, header-safe identifiers from clients. Never echo
        // arbitrary untrusted input into response headers or diagnostic contexts.
        if (!traceId.matches("[A-Za-z0-9._-]{1,64}")) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        if (!spanId.matches("[A-Za-z0-9._-]{1,32}")) {
            spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        String previousTrace = MDC.get(MDC_TRACE_ID);
        String previousSpan = MDC.get(MDC_SPAN_ID);
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_SPAN_ID, spanId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        response.setHeader(SPAN_ID_HEADER, spanId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Restore unrelated logging context instead of MDC.clear().
            if (previousTrace == null) MDC.remove(MDC_TRACE_ID);
            else MDC.put(MDC_TRACE_ID, previousTrace);
            if (previousSpan == null) MDC.remove(MDC_SPAN_ID);
            else MDC.put(MDC_SPAN_ID, previousSpan);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip actuator endpoints to avoid noise in health checks
        String path = request.getRequestURI();
        return path.startsWith("/actuator/");
    }
}