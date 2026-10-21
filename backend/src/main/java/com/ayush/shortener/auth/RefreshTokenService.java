package com.ayush.shortener.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh tokens with rotation and reuse detection.
 * Every use swaps the token for a new one in the same family. If an already-used token shows up again, someone
 * has a copy they shouldn't (theft, or a replayed request), so the whole family is revoked and everyone holding
 * a token from that login has to sign in again.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens;
    private final Clock clock;
    private final Duration ttl;

    RefreshTokenService(RefreshTokenRepository tokens, Clock clock, AppProperties properties) {
        this.tokens = tokens;
        this.clock = clock;
        this.ttl = properties.auth().refreshTokenTtl();
    }

    /** A token from this call is the raw value for the cookie; only its hash is stored. */
    public record Issued(String rawToken, long userId) {
    }

    /** Start a new login session (new family). */
    @Transactional
    public String start(long userId) {
        return create(userId, UUID.randomUUID());
    }

    /** Swap a valid token for a fresh one. Reuse of a spent token revokes its whole family. */
    // noRollbackFor: the family revocation on reuse must be committed even though we then throw.
    @Transactional(noRollbackFor = ApiException.class)
    public Issued rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = tokens.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("Invalid session"));
        if (tokens.revokeIfActive(current.getId(), now) == 0) {
            tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; family revoked", current.getUserId());
            throw ApiException.unauthorized("Invalid session");
        }
        if (!current.getExpiresAt().isAfter(now)) {
            throw ApiException.unauthorized("Session expired");
        }
        return new Issued(create(current.getUserId(), current.getFamilyId()), current.getUserId());
    }

    /** Logout: kill the whole login session this token belongs to. Unknown tokens are ignored. */
    @Transactional
    public void revoke(String rawToken) {
        tokens.findByTokenHash(hash(rawToken))
                .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    @Transactional
    public void revokeAllForUser(long userId) {
        tokens.revokeAllForUser(userId, clock.instant());
    }

    /** Daily housekeeping. Idempotent, so it is safe when several instances run it. */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void purgeExpired() {
        int removed = tokens.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
        log.info("Purged {} expired refresh tokens", removed);
    }

    private String create(long userId, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        tokens.save(new RefreshToken(userId, hash(raw), familyId, now.plus(ttl), now));
        return raw;
    }

    /** SHA-256 is enough here (not bcrypt): the input is 256 bits of randomness, not a guessable password. */
    static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
