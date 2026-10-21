package com.ayush.shortener.config;

import java.time.Clock;

import com.ayush.shortener.analytics.ClickStreamProperties;
import com.ayush.shortener.ratelimit.RateLimitProperties;
import com.ayush.shortener.redis.RedisProperties;
import com.ayush.shortener.web.WebProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, RedisProperties.class, RateLimitProperties.class, ClickStreamProperties.class, WebProperties.class})
class AppConfig {

    /** Injected wherever "now" matters so tests can pin time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
