package com.ayush.shortener.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.ayush.shortener.config.AppProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration ttl;

    JwtService(JwtEncoder encoder, Clock clock, AppProperties properties) {
        this.encoder = encoder;
        this.clock = clock;
        this.ttl = properties.auth().accessTokenTtl();
    }

    /** Short-lived and carries only the user id: anything else would go stale until it expires. */
    public String issueAccessToken(long userId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
