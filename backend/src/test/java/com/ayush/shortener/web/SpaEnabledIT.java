package com.ayush.shortener.web;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static com.ayush.shortener.auth.CsrfTestSupport.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The web app served from the same process (app.web.spa-enabled=true), against a tiny stand-in build in test resources. */
@SpringBootTest(properties = {"app.web.spa-enabled=true", "app.web.static-location=classpath:/spa-test/"})
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class SpaEnabledIT {

    private static final String STRICT_API_CSP = "default-src 'none'; frame-ancestors 'none'";

    @Autowired MockMvc mvc;

    @Test
    void theRootAndEveryClientRouteReturnTheAppShellWithoutSigningIn() throws Exception {
        for (String path : new String[] {"/", "/login", "/register", "/links", "/links/42", "/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("<div id=\"root\">")));
            mvc.perform(head(path)).andExpect(status().isOk());
        }
    }

    @Test
    void theRootNoLongerRedirectsToSwaggerButSwaggerStillWorks() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void theShellIsAlwaysRevalidatedButHashedFilesAreCachedForever() throws Exception {
        mvc.perform(get("/")).andExpect(header().string("Cache-Control", "no-cache"));
        mvc.perform(get("/login")).andExpect(header().string("Cache-Control", "no-cache"));

        mvc.perform(get("/assets/app-3f9a1c.js")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=31536000, public, immutable"));
        mvc.perform(get("/favicon.svg")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=86400")));
    }

    @Test
    void anUnknownApiPathNeverFallsBackToTheAppShell() throws Exception {
        // signed out: the normal 401, as before the web app existed
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());

        // signed in: a real JSON 404 (RFC 9457), never HTML
        MvcResult signedUp = mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"spa%d@example.com\",\"password\":\"correct horse battery\"}".formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        Cookie session = signedUp.getResponse().getCookie("access_token");
        MvcResult missing = mvc.perform(get("/api/does-not-exist").cookie(session)).andExpect(status().isNotFound()).andReturn();

        assertThat(missing.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(missing.getResponse().getContentAsString()).contains("\"status\":404").doesNotContain("<html");
        mvc.perform(get("/api/links/does/not/exist").cookie(session)).andExpect(status().isNotFound());
    }

    @Test
    void theAppPagesGetTheirOwnPolicyWhileTheApiKeepsTheStrictOne() throws Exception {
        for (String path : new String[] {"/", "/login", "/links/7", "/index.html", "/assets/app-3f9a1c.js", "/favicon.svg"}) {
            String csp = mvc.perform(get(path)).andReturn().getResponse().getHeader("Content-Security-Policy");
            assertThat(csp).as(path).contains("default-src 'self'", "script-src 'self'", "font-src 'self' data:",
                            "img-src 'self' data:", "connect-src 'self'", "frame-ancestors 'none'", "object-src 'none'")
                    .doesNotContain("*", "https:", "accounts.google.com"); // Google sign-in is not configured in this test
            assertThat(csp).as("no inline or eval scripts: " + path).doesNotContain("script-src 'self' 'unsafe");
        }
        for (String path : new String[] {"/api/links", "/api/auth/csrf", "/actuator/health", "/v3/api-docs"}) {
            mvc.perform(get(path)).andExpect(header().string("Content-Security-Policy", STRICT_API_CSP));
        }
        // and a short link (a redirect or a JSON error) is not an app page either
        mvc.perform(get("/no-such-code-here")).andExpect(header().string("Content-Security-Policy", STRICT_API_CSP));
    }

    @Test
    void theAppPagesAreNotFramableAndTheShellIsNotACacheableApiAnswer() throws Exception {
        mvc.perform(get("/")).andExpect(header().string("X-Frame-Options", "DENY"));
    }
}
