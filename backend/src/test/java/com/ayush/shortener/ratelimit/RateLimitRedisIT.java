package com.ayush.shortener.ratelimit;

import java.time.Duration;
import java.util.List;

import com.ayush.shortener.redis.RedisGateway;
import com.ayush.shortener.redis.RedisTestServer;
import com.ayush.shortener.redis.RequiresRedis;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static com.ayush.shortener.auth.CsrfTestSupport.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The same limits with Redis ON: the normal (not the tightened) numbers, counted in Redis, surviving a Redis outage. */
@RequiresRedis
@SpringBootTest(properties = "app.rate-limit.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class RateLimitRedisIT {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("app.redis.url", () -> RedisTestServer.get().url());
        registry.add("app.redis.key-prefix", RedisTestServer::uniquePrefix);
    }

    @Autowired MockMvc mvc;
    @Autowired RedisGateway redis;
    @Autowired MeterRegistry metrics;

    @AfterEach
    void restore() {
        RedisTestServer.get().ensureRunning();
    }

    static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    MvcResult login(String address) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).with(from(address)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"r%d@example.com\",\"password\":\"nope\"}".formatted(System.nanoTime()))).andReturn();
    }

    @Test
    void withRedisTheNormalLimitIsUsedAndItIsCountedInRedis() throws Exception {
        assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        String address = "192.0.2.10";
        double before = metrics.counter("ratelimit.requests", "policy", "auth-ip", "result", "allowed", "store", "redis").count();
        for (int i = 1; i <= 10; i++) {
            assertThat(login(address).getResponse().getStatus()).as("attempt %d", i).isEqualTo(401);
        }
        MvcResult blocked = login(address);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("RateLimit-Limit")).isEqualTo("10"); // not the degraded 5
        assertThat(metrics.counter("ratelimit.requests", "policy", "auth-ip", "result", "allowed", "store", "redis").count() - before).isEqualTo(10);
    }

    @Test
    void theStateLivesInRedisUnderHashedKeys() throws Exception {
        login("192.0.2.77");
        List<String> keys = redis.call(c -> c.keys(redis.key("rl:auth-ip:*")), List.<String>of());
        assertThat(keys).isNotEmpty();
        assertThat(String.join(",", keys)).doesNotContain("192.0.2.77");
    }

    @Test
    void ifRedisDiesSignInKeepsWorkingUnderTheTighterLimitAndRecoversLater() throws Exception {
        RedisTestServer server = RedisTestServer.get();
        String address = "192.0.2.50";
        server.stop();

        for (int i = 1; i <= 5; i++) {
            assertThat(login(address).getResponse().getStatus()).as("attempt %d with Redis down: a 401, never a 500", i).isEqualTo(401);
        }
        MvcResult blocked = login(address);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("RateLimit-Limit")).isEqualTo("5");   // tighter while Redis is away
        assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.DOWN);

        server.ensureRunning();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            redis.probe();
            assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        });
        assertThat(login("192.0.2.51").getResponse().getHeader("RateLimit-Limit")).isEqualTo("10"); // normal limits are back
    }
}
