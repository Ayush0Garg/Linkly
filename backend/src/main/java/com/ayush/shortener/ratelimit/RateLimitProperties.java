package com.ayush.shortener.ratelimit;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled  global switch (off in the test profile; also handy for load tests)
 * @param policies one entry per named policy, see {@link Policies}
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(boolean enabled, Map<String, Policy> policies) {

    /**
     * @param limit         requests allowed per {@code per} when Redis is available
     * @param degradedLimit the tighter limit used while Redis is off: it is then enforced per instance only, so it has
     *                      to be stricter to give the same protection
     * @param localOnly     false (the default when omitted): counted in Redis, across all instances. true: counted per
     *                      instance only (used for redirects, to keep an extra Redis round trip off the hottest path).
     *                      Phrased this way round so that leaving it out gives the safe, shared behaviour: a primitive
     *                      boolean that is omitted binds to false.
     */
    public record Policy(int limit, Duration per, int degradedLimit, boolean localOnly) {

        public boolean shared() {
            return !localOnly;
        }
    }

    public RateLimitProperties {
        if (enabled) {
            for (String name : Policies.ALL) {
                Policy policy = policies == null ? null : policies.get(name);
                if (policy == null || policy.limit() < 1 || policy.degradedLimit() < 1 || policy.per() == null) {
                    throw new IllegalStateException("app.rate-limit.policies." + name + " must define limit, per and degraded-limit");
                }
            }
        }
    }

    public Policy policy(String name) {
        return policies.get(name);
    }
}
