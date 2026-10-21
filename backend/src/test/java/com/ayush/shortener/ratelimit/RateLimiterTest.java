package com.ayush.shortener.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ayush.shortener.redis.RedisGateway;
import com.ayush.shortener.redis.RedisProperties;
import com.ayush.shortener.redis.RedisTestServer;
import com.ayush.shortener.redis.RequiresRedis;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The limiter's behaviour: local and Redis-backed, normal and degraded. */
class RateLimiterTest {

    static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";

    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    RedisGateway redis;
    RateLimiter limiter;
    RateLimiter second;

    static RateLimitProperties props(boolean enabled, Duration per) {
        return new RateLimitProperties(enabled, Map.of(
                Policies.AUTH_IP, new RateLimitProperties.Policy(6, per, 3, false),
                Policies.AUTH_EMAIL, new RateLimitProperties.Policy(6, per, 3, false),
                Policies.LINKS_CREATE, new RateLimitProperties.Policy(6, per, 3, false),
                Policies.QR, new RateLimitProperties.Policy(6, per, 3, false),
                Policies.REDIRECT, new RateLimitProperties.Policy(6, per, 3, true)));
    }

    static RedisProperties redisProps(String url) {
        return new RedisProperties(url, false, Duration.ofMillis(200), Duration.ofSeconds(2), "rl-test-" + System.nanoTime() + ":",
                new RedisProperties.Cache(Duration.ofMinutes(15), Duration.ofSeconds(30)));
    }

    RateLimiter limiter(String redisUrl, boolean enabled, Duration per) {
        redis = new RedisGateway(redisProps(redisUrl), metrics);
        redis.start();
        limiter = new RateLimiter(props(enabled, per), SECRET, redis, metrics);
        return limiter;
    }

    @AfterEach
    void close() {
        if (limiter != null) {
            limiter.stop();
        }
        if (second != null) {
            second.stop();
        }
        if (redis != null) {
            redis.stop();
        }
        if (RedisTestServer.available()) {
            RedisTestServer.get().ensureRunning();
        }
    }

    // ------------------------------------------------------------ Redis off: the tighter, per-instance path

    @Test
    void withRedisOffTheTighterDegradedLimitApplies() {
        limiter("", true, Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            Decision d = limiter.check(Policies.AUTH_IP, "ip:1.2.3.4");
            assertThat(d.allowed()).as("request %d", i + 1).isTrue();
            assertThat(d.limit()).isEqualTo(3);        // 3, not the normal 6
            assertThat(d.shared()).isFalse();
        }
        Decision denied = limiter.check(Policies.AUTH_IP, "ip:1.2.3.4");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
    }

    @Test
    void eachClientHasItsOwnAllowance() {
        limiter("", true, Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            limiter.check(Policies.AUTH_IP, "ip:1.1.1.1");
        }
        assertThat(limiter.check(Policies.AUTH_IP, "ip:1.1.1.1").allowed()).isFalse();
        assertThat(limiter.check(Policies.AUTH_IP, "ip:2.2.2.2").allowed()).isTrue();
    }

    @Test
    void eachPolicyHasItsOwnAllowanceEvenForTheSameClient() {
        limiter("", true, Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            limiter.check(Policies.QR, "user:7");
        }
        assertThat(limiter.check(Policies.QR, "user:7").allowed()).isFalse();
        assertThat(limiter.check(Policies.LINKS_CREATE, "user:7").allowed()).isTrue();
    }

    @Test
    void theAllowanceRefillsOverTime() throws Exception {
        limiter("", true, Duration.ofSeconds(3)); // degraded: 3 per 3 s = one token per second
        for (int i = 0; i < 3; i++) {
            limiter.check(Policies.AUTH_IP, "ip:9.9.9.9");
        }
        Decision denied = limiter.check(Policies.AUTH_IP, "ip:9.9.9.9");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 3L);

        Thread.sleep(1_200);
        assertThat(limiter.check(Policies.AUTH_IP, "ip:9.9.9.9").allowed()).isTrue();
        assertThat(limiter.check(Policies.AUTH_IP, "ip:9.9.9.9").allowed()).isFalse(); // only one token came back
    }

    @Test
    void whenSwitchedOffNothingIsEverLimited() {
        limiter("", false, Duration.ofMinutes(1));
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.check(Policies.AUTH_IP, "ip:1.1.1.1").allowed()).isTrue();
        }
        assertThat(limiter.enabled()).isFalse();
    }

    @Test
    void requireThrowsWithTheDecisionWhenOverTheLimit() {
        limiter("", true, Duration.ofMinutes(1));
        for (int i = 0; i < 3; i++) {
            limiter.require(Policies.AUTH_EMAIL, "someone@example.com");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limiter.require(Policies.AUTH_EMAIL, "someone@example.com"))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.policy()).isEqualTo(Policies.AUTH_EMAIL);
                    assertThat(e.decision().retryAfterSeconds()).isPositive();
                });
    }

    @Test
    void everyDecisionIsCountedInMetricsByPolicyResultAndStore() {
        limiter("", true, Duration.ofMinutes(1));
        for (int i = 0; i < 5; i++) {
            limiter.check(Policies.QR, "user:1");
        }
        assertThat(metrics.counter("ratelimit.requests", "policy", "qr", "result", "allowed", "store", "local").count()).isEqualTo(3);
        assertThat(metrics.counter("ratelimit.requests", "policy", "qr", "result", "denied", "store", "local").count()).isEqualTo(2);
    }

    @Test
    void theLocalTableForgetsIdleClientsAndStaysBounded() {
        limiter("", true, Duration.ofMinutes(1));
        for (int i = 0; i < 50; i++) {
            limiter.check(Policies.AUTH_IP, "ip:10.0.0." + i);
        }
        assertThat(limiter.localBucketCount()).isEqualTo(50);

        limiter.sweepLocal(System.nanoTime() + Duration.ofMinutes(10).toNanos()); // ten idle minutes later
        assertThat(limiter.localBucketCount()).isZero();

        limiter.maxLocalBuckets = 20;                                              // a flood of distinct clients
        for (int i = 0; i < 100; i++) {
            limiter.check(Policies.AUTH_IP, "ip:flood-" + i);
        }
        limiter.sweepLocal();
        assertThat(limiter.localBucketCount()).isLessThanOrEqualTo(100 - 5);        // the oldest quarter is gone
    }

    // ------------------------------------------------------------ with Redis: shared across instances

    @Test
    @RequiresRedisCheck
    void withRedisTheNormalLimitApplies() {
        limiter(RedisTestServer.get().url(), true, Duration.ofMinutes(1));
        for (int i = 0; i < 6; i++) {
            Decision d = limiter.check(Policies.AUTH_IP, "ip:5.5.5.5");
            assertThat(d.allowed()).as("request %d", i + 1).isTrue();
            assertThat(d.limit()).isEqualTo(6);       // the normal limit, not the degraded 3
            assertThat(d.shared()).isTrue();
        }
        assertThat(limiter.check(Policies.AUTH_IP, "ip:5.5.5.5").allowed()).isFalse();
    }

    @Test
    @RequiresRedisCheck
    void twoInstancesDrawFromTheSameBucket() {
        String url = RedisTestServer.get().url();
        RedisProperties shared = redisProps(url);
        redis = new RedisGateway(shared, metrics);
        redis.start();
        limiter = new RateLimiter(props(true, Duration.ofMinutes(1)), SECRET, redis, metrics);
        second = new RateLimiter(props(true, Duration.ofMinutes(1)), SECRET, redis, metrics); // "another instance"

        int allowed = 0;
        for (int i = 0; i < 4; i++) {
            allowed += limiter.check(Policies.LINKS_CREATE, "user:42").allowed() ? 1 : 0;
        }
        for (int i = 0; i < 4; i++) {
            allowed += second.check(Policies.LINKS_CREATE, "user:42").allowed() ? 1 : 0;
        }
        assertThat(allowed).as("6 allowed across both instances, not 6 each").isEqualTo(6);
    }

    @Test
    @RequiresRedisCheck
    void keysHoldAHashNeverTheRawAddressOrEmailAndExpireByThemselves() {
        limiter(RedisTestServer.get().url(), true, Duration.ofSeconds(30));
        limiter.check(Policies.AUTH_IP, "ip:203.0.113.77");
        limiter.check(Policies.AUTH_EMAIL, "victim@example.com");

        List<String> keys = redis.call(c -> c.keys(redis.key("rl:*")), List.<String>of());
        assertThat(keys).hasSize(2);
        for (String key : keys) {
            assertThat(key).doesNotContain("203.0.113.77").doesNotContain("victim").doesNotContain("example.com");
            assertThat(redis.call(c -> c.pttl(key), -3L)).as("%s has a TTL", key).isPositive();
        }
        assertThat(String.join(",", keys)).contains("rl:auth-ip:6:").contains("rl:auth-email:6:"); // policy and limit are visible, the subject is not
    }

    @Test
    @RequiresRedisCheck
    void aPolicyThatIsPerInstanceOnlyNeverTouchesRedis() {
        limiter(RedisTestServer.get().url(), true, Duration.ofMinutes(1));
        for (int i = 0; i < 6; i++) {
            Decision d = limiter.check(Policies.REDIRECT, "ip:7.7.7.7");
            assertThat(d.allowed()).isTrue();
            assertThat(d.shared()).isFalse();
        }
        assertThat(limiter.check(Policies.REDIRECT, "ip:7.7.7.7").allowed()).isFalse();
        assertThat(redis.call(c -> c.keys(redis.key("rl:*")), List.<String>of())).isEmpty();
    }

    @Test
    @RequiresRedisCheck
    void whenRedisDiesTheCheckStillAnswersAndSwitchesToTheTighterLimit() {
        RedisTestServer server = RedisTestServer.get();
        limiter(server.url(), true, Duration.ofMinutes(1));
        assertThat(limiter.check(Policies.AUTH_IP, "ip:8.8.8.8").shared()).isTrue();

        server.stop();
        Decision during = limiter.check(Policies.AUTH_IP, "ip:8.8.8.8"); // no exception, no 500
        assertThat(during.allowed()).isTrue();
        assertThat(during.shared()).isFalse();
        assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.DOWN);
        assertThat(limiter.check(Policies.AUTH_IP, "ip:8.8.8.8").limit()).isEqualTo(3); // now the degraded limit

        server.ensureRunning();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            redis.probe();
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        });
        assertThat(limiter.check(Policies.AUTH_IP, "ip:8.8.8.8").shared()).isTrue(); // and back to the shared one
    }

    /** Skips a single test when there is no redis-server (see RequiresRedis for the class-level equivalent). */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @org.junit.jupiter.api.extension.ExtendWith(RequiresRedis.Condition.class)
    @interface RequiresRedisCheck {
    }

    @SuppressWarnings("unused")
    private static final Set<String> UNUSED = Set.of();
}
