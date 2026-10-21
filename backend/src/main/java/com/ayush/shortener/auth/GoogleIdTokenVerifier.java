package com.ayush.shortener.auth;

import java.util.Map;
import java.util.Set;

import com.ayush.shortener.common.ApiException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies the ID token the browser gets from "Sign in with Google": signature (against Google's published keys),
 * expiry, issuer, that it was issued for OUR client id, and that Google vouches for the email address.
 */
public class GoogleIdTokenVerifier {

    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    public record GoogleProfile(String sub, String email, String name, String picture) {
    }

    private final JwtDecoder decoder;

    /** @param decoder null means Google sign-in is not configured */
    GoogleIdTokenVerifier(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    static GoogleIdTokenVerifier forClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return new GoogleIdTokenVerifier(null);
        }
        return new GoogleIdTokenVerifier(configure(NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build(), clientId));
    }

    /**
     * Google sometimes sets iss to the bare "accounts.google.com", which Spring's default claim conversion
     * (iss must be a URL) would reject with an exception, so keep it as a plain string.
     */
    static NimbusJwtDecoder configure(NimbusJwtDecoder decoder, String clientId) {
        decoder.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(
                Map.of("iss", issuer -> issuer == null ? null : issuer.toString())));
        decoder.setJwtValidator(validator(clientId));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), // expiry / not-before
                claim("iss", jwt -> ISSUERS.contains(jwt.getClaimAsString("iss"))),
                claim("aud", jwt -> jwt.getAudience() != null && jwt.getAudience().contains(clientId)),
                claim("email_verified", jwt -> Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))));
    }

    private static OAuth2TokenValidator<Jwt> claim(String name, java.util.function.Predicate<Jwt> check) {
        return jwt -> check.test(jwt)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Bad claim: " + name, null));
    }

    public GoogleProfile verify(String idToken) {
        if (decoder == null) {
            throw ApiException.unavailable("Google sign-in is not configured");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw ApiException.unauthorized("Invalid Google token");
        }
        String email = jwt.getClaimAsString("email");
        if (jwt.getSubject() == null || email == null || email.isBlank()) {
            throw ApiException.unauthorized("Invalid Google token");
        }
        return new GoogleProfile(jwt.getSubject(), email, jwt.getClaimAsString("name"), jwt.getClaimAsString("picture"));
    }
}
