package com.ayush.shortener.redis;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Redis being away is not an outage here, it is a reduced mode, and the health endpoint says exactly that:
 * UP when normal, DEGRADED (still HTTP 200, so traffic keeps flowing) when Redis is off or unreachable.
 */
@Component("redis")
class RedisHealthIndicator implements HealthIndicator {

    private final RedisGateway redis;

    RedisHealthIndicator(RedisGateway redis) {
        this.redis = redis;
    }

    @Override
    public Health health() {
        return switch (redis.mode()) {
            case UP -> Health.up().withDetail("mode", "normal").build();
            case OFF -> Health.status("DEGRADED").withDetail("mode", "degraded").withDetail("reason", redis.reason()).build();
            case DOWN -> Health.status("DEGRADED").withDetail("mode", "degraded").withDetail("reason", redis.reason()).build();
        };
    }
}
