package com.ayush.shortener.auth;

import java.time.Duration;

import com.ayush.shortener.config.AppProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the two auth cookies. Both are HttpOnly (JavaScript can never read them, which is the point of using
 * cookies over localStorage) and each is scoped to the narrowest path that needs it:
 * - access token: only sent to /api, so redirect traffic on /{code} never carries credentials
 * - refresh token: only sent to /api/auth, so it is exposed to as few requests as possible
 */
@Component
class AuthCookies {

    static final String ACCESS = "access_token";
    static final String REFRESH = "refresh_token";

    private final boolean secure;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    AuthCookies(AppProperties properties) {
        this.secure = properties.auth().cookieSecure();
        this.accessTtl = properties.auth().accessTokenTtl();
        this.refreshTtl = properties.auth().refreshTokenTtl();
    }

    ResponseCookie access(String token) {
        return build(ACCESS, token, "/api", "Lax", accessTtl);
    }

    ResponseCookie refresh(String token) {
        return build(REFRESH, token, "/api/auth", "Strict", refreshTtl);
    }

    ResponseCookie clearAccess() {
        return build(ACCESS, "", "/api", "Lax", Duration.ZERO);
    }

    ResponseCookie clearRefresh() {
        return build(REFRESH, "", "/api/auth", "Strict", Duration.ZERO);
    }

    private ResponseCookie build(String name, String value, String path, String sameSite, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
