package com.ayush.shortener.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param url           blank means Redis is OFF (degraded mode)
 * @param required      refuse to start unless Redis is reachable
 * @param commandTimeout hard cap on any single Redis command
 * @param probeInterval how often the connection is checked (and re-established when it is down)
 */
@ConfigurationProperties("app.redis")
public record RedisProperties(String url, boolean required, Duration commandTimeout, Duration probeInterval,
                              String keyPrefix, Cache cache) {

    /**
     * @param linkTtl how long a link stays cached; also the upper bound on how stale an entry can ever be
     * @param missTtl how long "no such code" or "gone" is remembered
     */
    public record Cache(Duration linkTtl, Duration missTtl) {
    }
}
