package com.ayush.shortener.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RequiresRedis
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class RedisOnIT {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("app.redis.url", () -> RedisTestServer.get().url());
        registry.add("app.redis.key-prefix", RedisTestServer::uniquePrefix);
    }

    @Autowired MockMvc mvc;
    @Autowired RedisGateway redis;
    @Autowired io.micrometer.core.instrument.MeterRegistry metrics;

    @Test
    void withRedisReachableTheModeIsNormal() throws Exception {
        assertThat(redis.mode()).isEqualTo(RedisGateway.Mode.UP);
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.redis.status").value("UP"));
        assertThat(metrics.get("redis.up").gauge().value()).isEqualTo(1);
    }
}
