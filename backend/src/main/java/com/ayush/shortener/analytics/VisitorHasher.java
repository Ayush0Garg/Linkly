package com.ayush.shortener.analytics;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ayush.shortener.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Privacy-preserving "unique visitor" id: HMAC(daily key, ip + user agent), truncated.
 * - The raw IP is never stored; the hash cannot be reversed without the secret salt.
 * - The key is derived per UTC day, so the same person gets a different hash tomorrow. Visitors cannot be tracked
 *   across days, which is the point - and it means unique counts are "per day" by construction.
 */
@Component
public class VisitorHasher {

    private static final String HMAC = "HmacSHA256";

    private record DayKey(LocalDate day, byte[] key) {
    }

    private final byte[] salt;
    private final AtomicReference<DayKey> current = new AtomicReference<>();

    public VisitorHasher(AppProperties properties) {
        String configured = properties.analytics().visitorSalt();
        if (configured == null || configured.getBytes(StandardCharsets.UTF_8).length < 16) {
            throw new IllegalStateException("app.analytics.visitor-salt (ANALYTICS_SALT) must be at least 16 bytes");
        }
        this.salt = configured.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String ip, String userAgent, Instant at) {
        byte[] key = dayKey(at.atZone(ZoneOffset.UTC).toLocalDate());
        byte[] digest = hmac(key, (ip == null ? "" : ip) + '|' + (userAgent == null ? "" : userAgent));
        return HexFormat.of().formatHex(digest, 0, 16);
    }

    private byte[] dayKey(LocalDate day) {
        DayKey cached = current.get();
        if (cached != null && cached.day().equals(day)) {
            return cached.key();
        }
        DayKey fresh = new DayKey(day, hmac(salt, "visitor-day:" + day));
        current.set(fresh);
        return fresh.key();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
