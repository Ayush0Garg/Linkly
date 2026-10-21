package com.ayush.shortener.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(String baseUrl, Code code, Auth auth, Analytics analytics) {

    /**
     * @param minLength length of the generated part, not counting the prefix
     * @param prefix    brand marker put in front of every generated code (e.g. "linkly-"); custom aliases may not use it
     */
    public record Code(String alphabet, int minLength, String prefix) {
    }

    /**
     * @param queueCapacity  clicks buffered in memory between redirect and database; when full, new clicks are dropped
     * @param batchSize      rows per bulk insert
     * @param flushInterval  how often the buffer is drained
     * @param visitorSalt    secret mixed into the daily visitor hash; at least 16 bytes, from the environment only
     * @param geoipDb        path to a MaxMind-format country database (.mmdb); blank disables country lookup
     * @param retentionDays  raw click events older than this are dropped (daily rollups are kept)
     */
    public record Analytics(int queueCapacity, int batchSize, Duration flushInterval, String visitorSalt,
                            String geoipDb, int retentionDays) {
    }

    /**
     * @param jwtSecret      HMAC key for our own access tokens; at least 32 bytes, from the environment only
     * @param cookieSecure   mark auth cookies Secure (must be true anywhere except plain-http localhost)
     * @param googleClientId OAuth client id the Google ID token must be issued for; blank disables Google sign-in
     */
    public record Auth(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl,
                       boolean cookieSecure, int bcryptStrength, String googleClientId) {
    }
}
