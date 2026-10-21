package com.ayush.shortener.analytics;

import java.time.Duration;
import java.time.Instant;

import com.ayush.shortener.link.CreateLinkCommand;
import com.ayush.shortener.link.Link;
import com.ayush.shortener.link.LinkService;
import com.ayush.shortener.redis.RedisGateway;
import com.ayush.shortener.redis.RedisTestServer;
import com.ayush.shortener.redis.RequiresRedis;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The switch: with the stream disabled, clicks use the in-process queue even though Redis is up. */
@RequiresRedis
@SpringBootTest(properties = "app.analytics.stream.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ClickStreamDisabledIT {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("app.redis.url", () -> RedisTestServer.get().url());
        registry.add("app.redis.key-prefix", RedisTestServer::uniquePrefix);
    }

    @Autowired MockMvc mvc;
    @Autowired ClickRecorder recorder;
    @Autowired RedisGateway redis;
    @Autowired LinkService links;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void clicksGoThroughTheLocalQueueAndTheStreamIsNeverTouched() throws Exception {
        long userId = users.save(new User("sub-" + System.nanoTime(), "x" + System.nanoTime() + "@example.com", "X", null, Instant.now())).getId();
        Link link = links.create(userId, new CreateLinkCommand("https://example.com/local", null, null));
        assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + link.getCode()).header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0"))
                    .andExpect(status().isFound());
        }
        assertThat(recorder.flush()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM click_events WHERE link_id = ?", Long.class, link.getId())).isEqualTo(3L);
        // this context's own stream was never created (other test contexts share the Redis server, so look at our prefix only)
        assertThat(redis.call(c -> c.exists(redis.key("clicks")), -1L)).isZero();
        assertThat(Duration.ZERO).isNotNull();
    }
}
