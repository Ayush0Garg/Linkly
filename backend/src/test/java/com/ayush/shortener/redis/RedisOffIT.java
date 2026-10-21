package com.ayush.shortener.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** No REDIS_URL at all: the whole application must still start, serve, and say that it is degraded. */
@SpringBootTest(properties = "test.context=redis-off")  // unique property: forces a fresh context so the startup banner is printed
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@ExtendWith(OutputCaptureExtension.class)
class RedisOffIT {

    @Autowired MockMvc mvc;
    @Autowired RedisGateway redis;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;
    @Autowired com.ayush.shortener.link.LinkService links;
    @Autowired com.ayush.shortener.user.UserRepository users;

    @Test
    void theApplicationStartsAndAnnouncesThatRedisIsOff(CapturedOutput output) {
        assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.OFF);
        assertThat(output.getAll())
                .contains("REDIS IS OFF: running in DEGRADED mode")
                .contains("REDIS_URL is not set")
                .contains("redirect cache is off");
    }

    @Test
    void healthSaysDegradedButStillAnswers200SoTrafficKeepsFlowing() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.components.redis.status").value("DEGRADED"));
    }

    @Test
    void theReadinessProbeStaysReadyWhileDegraded() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }

    @Test
    void redirectsStillWorkWithNoRedisAndEachOneIsCountedAsServedWithoutTheCache() throws Exception {
        long userId = users.save(new com.ayush.shortener.user.User("sub-" + System.nanoTime(), "off" + System.nanoTime() + "@example.com", "O", null, java.time.Instant.now())).getId();
        var link = links.create(userId, new com.ayush.shortener.link.CreateLinkCommand("https://example.com/no-redis", null, null));
        double before = metrics.counter("cache.link", "result", "unavailable").count();

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + link.getCode())).andExpect(status().isFound())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "https://example.com/no-redis"));
        }
        assertThat(metrics.counter("cache.link", "result", "unavailable").count() - before).isEqualTo(3);
        links.deactivate(userId, link.getId()); // invalidation is a quiet no-op, not an error
        mvc.perform(get("/" + link.getCode())).andExpect(status().isGone());
    }

    @Test
    void theMetricShowsRedisIsDown() {
        assertThat(metrics.get("redis.up").gauge().value()).isZero();
    }
}
