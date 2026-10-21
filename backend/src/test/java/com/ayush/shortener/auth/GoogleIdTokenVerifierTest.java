package com.ayush.shortener.auth;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;

import com.ayush.shortener.common.ApiException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the claim checks with tokens we sign ourselves; the signature check is Nimbus's job. */
class GoogleIdTokenVerifierTest {

    private static final String CLIENT_ID = "my-client-id.apps.googleusercontent.com";

    private static NimbusJwtEncoder encoder;
    private static GoogleIdTokenVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));

        verifier = new GoogleIdTokenVerifier(GoogleIdTokenVerifier.configure(
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic()).build(), CLIENT_ID));
    }

    private static JwtClaimsSet.Builder validClaims() {
        return JwtClaimsSet.builder()
                .issuer("https://accounts.google.com")
                .audience(java.util.List.of(CLIENT_ID))
                .subject("1234567890")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(30)))
                .claim("email", "Someone@Example.com")
                .claim("email_verified", true)
                .claim("name", "Some One")
                .claim("picture", "https://lh3.example/pic.jpg");
    }

    private static String sign(JwtClaimsSet.Builder claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }

    @Test
    void acceptsAValidToken() {
        var profile = verifier.verify(sign(validClaims()));
        assertThat(profile.sub()).isEqualTo("1234567890");
        assertThat(profile.email()).isEqualTo("Someone@Example.com");
        assertThat(profile.name()).isEqualTo("Some One");
    }

    @Test
    void acceptsTheBareIssuerForm() {
        assertThat(verifier.verify(sign(validClaims().issuer("accounts.google.com"))).sub()).isEqualTo("1234567890");
    }

    @Test
    void rejectsATokenIssuedForAnotherApp() {
        assertRejected(sign(validClaims().audience(java.util.List.of("someone-elses-client-id"))));
    }

    @Test
    void rejectsAWrongIssuer() {
        assertRejected(sign(validClaims().issuer("https://evil.example.com")));
    }

    @Test
    void rejectsAnExpiredToken() {
        assertRejected(sign(validClaims()
                .issuedAt(Instant.now().minus(Duration.ofHours(2)))
                .expiresAt(Instant.now().minus(Duration.ofHours(1)))));
    }

    @Test
    void rejectsAnUnverifiedEmail() {
        assertRejected(sign(validClaims().claim("email_verified", false)));
    }

    @Test
    void rejectsATokenWithoutEmail() {
        JwtClaimsSet.Builder claims = validClaims();
        claims.claims(c -> c.remove("email"));
        assertRejected(sign(claims));
    }

    @Test
    void rejectsGarbageAndTokensSignedByAnotherKey() throws Exception {
        assertRejected("not-a-jwt");

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair other = generator.generateKeyPair();
        RSAKey otherJwk = new RSAKey.Builder((RSAPublicKey) other.getPublic()).privateKey((RSAPrivateKey) other.getPrivate()).build();
        String forged = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(otherJwk)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), validClaims().build()))
                .getTokenValue();
        assertRejected(forged);
    }

    @Test
    void isUnavailableWhenNoClientIdIsConfigured() {
        assertThatThrownBy(() -> GoogleIdTokenVerifier.forClientId("").verify("anything"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(503));
    }

    private static void assertRejected(String token) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(401));
    }
}
