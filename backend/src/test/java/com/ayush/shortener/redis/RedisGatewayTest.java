package com.ayush.shortener.redis;

import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** The three modes (OFF, UP, DOWN), against a real Redis that the tests can kill and bring back. */
@RequiresRedis
class RedisGatewayTest {

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private RedisGateway gateway;

    static RedisProperties props(String url, boolean required) {
        return new RedisProperties(url, required, Duration.ofMillis(100), Duration.ofSeconds(2), "t:",
                new RedisProperties.Cache(Duration.ofMinutes(15), Duration.ofSeconds(30)));
    }

    RedisGateway started(String url, boolean required) {
        gateway = new RedisGateway(props(url, required), metrics);
        gateway.start();
        return gateway;
    }

    @AfterEach
    void close() {
        if (gateway != null) {
            gateway.stop();
        }
        RedisTestServer.get().ensureRunning(); // never leave the shared server stopped for the next test
    }

    // ---- OFF

    @Test
    void withNoUrlRedisIsOffAndEveryCallGetsItsFallback() {
        started("", false);
        assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.OFF);
        assertThat(gateway.available()).isFalse();
        assertThat(gateway.call(c -> c.get("x"), "fallback")).isEqualTo("fallback");
        assertThat(metrics.counter("redis.fallbacks").count()).isEqualTo(1);
        assertThat(metrics.get("redis.up").gauge().value()).isZero();
    }

    @Test
    void theStartupBannerSaysPlainlyThatRedisIsOffAndWhatThatMeans() {
        started("", false);
        assertThat(gateway.banner())
                .contains("REDIS IS OFF")
                .contains("DEGRADED")
                .contains("REDIS_URL is not set")
                .contains("redirect cache is off")
                .contains("click events are buffered in this process only")
                .contains("REDIS_REQUIRED=true");
    }

    @Test
    void healthReportsDegradedNotDownWhenRedisIsOff() {
        started("", false);
        Health health = new RedisHealthIndicator(gateway).health();
        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(health.getDetails()).containsEntry("mode", "degraded").containsEntry("reason", "REDIS_URL is not set");
    }

    // ---- UP

    @Test
    void whenReachableItWorksAndSaysNormal() {
        started(RedisTestServer.get().url(), false);
        assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.UP);
        gateway.call(c -> c.set(gateway.key("k"), "v"), null);
        assertThat(gateway.call(c -> c.get(gateway.key("k")), "fallback")).isEqualTo("v");
        assertThat(gateway.key("k")).isEqualTo("t:k");
        assertThat(metrics.get("redis.up").gauge().value()).isEqualTo(1);
        assertThat(metrics.counter("redis.fallbacks").count()).isZero();
        assertThat(new RedisHealthIndicator(gateway).health().getStatus().getCode()).isEqualTo("UP");
    }

    // ---- DOWN

    @Test
    void configuredButUnreachableStillStartsAndSaysWhy() {
        String url = "redis://localhost:" + RedisTestServer.closedPort();
        started(url, false);
        assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.DOWN);
        assertThat(gateway.banner()).contains("REDIS IS OFF").contains("cannot reach " + url);
        assertThat(new RedisHealthIndicator(gateway).health().getStatus().getCode()).isEqualTo("DEGRADED");
    }

    @Test
    void anUnreachableRedisIsSkippedInstantlyNotWaitedOn() {
        started("redis://localhost:" + RedisTestServer.closedPort(), false);
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            assertThat(gateway.call(c -> c.get("x"), "fallback")).isEqualTo("fallback");
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100)); // 200 calls
    }

    @Test
    void requiredAndUnreachableRefusesToStart() {
        assertThatThrownBy(() -> started("redis://localhost:" + RedisTestServer.closedPort(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_REQUIRED")
                .hasMessageContaining("not reachable");
    }

    @Test
    void requiredAndReachableStartsNormally() {
        started(RedisTestServer.get().url(), true);
        assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.UP);
    }

    @Test
    void passwordsNeverReachTheLogsTheBannerOrTheErrors() {
        String url = "redis://:s3cret-pass@localhost:" + RedisTestServer.closedPort();
        assertThatThrownBy(() -> started(url, true)).hasMessageNotContaining("s3cret-pass");
        gateway = new RedisGateway(props(url, false), metrics);
        gateway.start();
        assertThat(gateway.banner()).doesNotContain("s3cret-pass");
        assertThat(gateway.safeUrl()).doesNotContain("s3cret-pass").startsWith("redis://localhost:");
    }

    // ---- transitions

    @Test
    void losingRedisFlipsToDegradedAtOnceAndGettingItBackRestoresNormal() {
        RedisTestServer server = RedisTestServer.get();
        started(server.url(), false);
        assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.UP);

        server.stop();
        // the very next command notices, returns its fallback, and flips the mode: no 30-second wait
        assertThat(gateway.call(c -> c.get("x"), "fallback")).isEqualTo("fallback");
        assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.DOWN);
        assertThat(new RedisHealthIndicator(gateway).health().getStatus().getCode()).isEqualTo("DEGRADED");
        assertThat(metrics.get("redis.up").gauge().value()).isZero();

        server.ensureRunning();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            gateway.probe(); // in the app this runs every probe-interval
            assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.UP);
        });
        gateway.call(c -> c.set(gateway.key("after"), "ok"), null);
        assertThat(gateway.call(c -> c.get(gateway.key("after")), "fallback")).isEqualTo("ok");
    }

    /** Keeps the server busy for ~600 ms: far beyond the 100 ms command timeout. */
    private static final String BUSY = "local t=redis.call('TIME'); local s=t[1]*1000000+t[2]; "
            + "while true do local n=redis.call('TIME'); if n[1]*1000000+n[2]-s > 600000 then break end end return 1";

    @Test
    void aSlowRedisCannotMakeACallSlow() {
        started(RedisTestServer.get().url(), false);
        long start = System.nanoTime();
        Object result = gateway.call(c -> c.eval(BUSY, io.lettuce.core.ScriptOutputType.INTEGER), "fallback");
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(result).isEqualTo("fallback");
        assertThat(took).isLessThan(Duration.ofMillis(400));
        awaitAnswering();
    }

    @Test
    void oneSlowReplyIsNotAnOutage() {
        started(RedisTestServer.get().url(), false);
        gateway.call(c -> c.eval(BUSY, io.lettuce.core.ScriptOutputType.INTEGER), "fallback"); // one timeout

        assertThat(gateway.mode()).as("a single timeout must not switch the whole application to degraded mode")
                .isEqualTo(RedisGateway.Mode.UP);
        awaitAnswering();
    }

    @Test
    void aRedisThatKeepsTimingOutIsTreatedAsDown() {
        started(RedisTestServer.get().url(), false);
        for (int i = 0; i < RedisGateway.TIMEOUT_STRIKES - 1; i++) {
            gateway.call(c -> c.get(gateway.key("x")), "fallback"); // fine, but...
        }
        gateway.call(c -> c.eval(BUSY, io.lettuce.core.ScriptOutputType.INTEGER), "fallback"); // strike 1 (blocks the server)
        for (int i = 1; i < RedisGateway.TIMEOUT_STRIKES; i++) {
            assertThat(gateway.mode()).as("only %d timeouts so far", i).isEqualTo(RedisGateway.Mode.UP);
            gateway.call(c -> c.get(gateway.key("x")), "fallback");   // queued behind the busy script: strikes 2..N
        }
        assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.DOWN);
        assertThat(gateway.reason()).contains("in a row timed out");
        awaitAnswering();
    }

    @Test
    void anAnswerBetweenTimeoutsStartsTheCountAgain() throws Exception {
        started(RedisTestServer.get().url(), false);
        for (int round = 0; round < 3; round++) {
            // RedisGateway.TIMEOUT_STRIKES - 1 timeouts, then a good answer: never enough in a row
            gateway.call(c -> c.eval(BUSY, io.lettuce.core.ScriptOutputType.INTEGER), "fallback");
            for (int i = 2; i < RedisGateway.TIMEOUT_STRIKES; i++) {
                gateway.call(c -> c.get(gateway.key("x")), "fallback");
            }
            awaitAnswering();
            assertThat(gateway.call(c -> c.ping(), "fallback")).isEqualTo("PONG");
        }
        assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.UP);
    }

    /** Waits for the busy script to end and Redis to answer again (and for the gateway to be UP). */
    private void awaitAnswering() {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            gateway.probe();
            assertThat(gateway.mode()).isEqualTo(RedisGateway.Mode.UP);
            assertThat(gateway.call(c -> c.ping(), "fallback")).isEqualTo("PONG");
        });
    }
}
