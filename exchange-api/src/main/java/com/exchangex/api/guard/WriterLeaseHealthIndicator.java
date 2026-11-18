package com.exchangex.api.guard;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public final class WriterLeaseHealthIndicator implements HealthIndicator {
    private final WriterLease lease;
    public WriterLeaseHealthIndicator(WriterLease lease) { this.lease = lease; }
    @Override public Health health() {
        return lease.healthy() ? Health.up().build() : Health.down().build();
    }
}
