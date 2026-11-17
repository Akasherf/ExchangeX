package com.exchangex.api.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for observability components.
 * <p>
 * Registers:
 * - CorrelationIdFilter (via component scan, ordered first)
 * - MetricsInterceptor for API request metrics
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final MetricsInterceptor metricsInterceptor;

    public WebMvcConfig(MetricsInterceptor metricsInterceptor) {
        this.metricsInterceptor = metricsInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Add metrics interceptor for all API endpoints
        registry.addInterceptor(metricsInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/actuator/**");
    }
}