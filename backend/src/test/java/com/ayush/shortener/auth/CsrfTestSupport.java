package com.ayush.shortener.auth;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Sends a genuine double-submit CSRF pair (XSRF-TOKEN cookie + X-XSRF-TOKEN header).
 * Used instead of spring-security-test's csrf(), which permanently swaps the CSRF repository for the whole
 * cached test context and so hides the real cookie behaviour from every test that runs after it.
 */
public final class CsrfTestSupport {

    private static final String TOKEN = "test-csrf-token-value";

    private CsrfTestSupport() {
    }

    public static RequestPostProcessor csrf() {
        return request -> {
            List<Cookie> cookies = new ArrayList<>();
            if (request.getCookies() != null) {
                cookies.addAll(List.of(request.getCookies()));
            }
            cookies.add(new Cookie("XSRF-TOKEN", TOKEN));
            request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN", TOKEN);
            return request;
        };
    }
}
