package com.ayush.shortener.auth;

import jakarta.servlet.http.Cookie;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static com.ayush.shortener.auth.CsrfTestSupport.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class LinkApiIT {

    @Autowired MockMvc mvc;

    Cookie alice;
    Cookie bob;

    @BeforeEach
    void signUp() throws Exception {
        alice = signUp("alice");
        bob = signUp("bob");
    }

    Cookie signUp(String who) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s%d@example.com\",\"password\":\"correct horse battery\"}".formatted(who, System.nanoTime())))
                .andExpect(status().isCreated()).andReturn();
        return result.getResponse().getCookie("access_token");
    }

    MockHttpServletRequestBuilder as(Cookie user, MockHttpServletRequestBuilder request) {
        return request.cookie(user);
    }

    String createLink(Cookie user, String body) throws Exception {
        return mvc.perform(as(user, post("/api/links")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void everyLinkEndpointRequiresSignIn() throws Exception {
        mvc.perform(get("/api/links")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/links").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"https://example.com\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/links/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/links/1").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void createReturnsShortUrlAndTheLinkRedirects() throws Exception {
        String json = createLink(alice, "{\"longUrl\":\"https://example.com/landing?a=1\"}");

        String code = JsonPath.read(json, "$.code");
        assertThat(code).startsWith("linkly-");
        assertThat((String) JsonPath.read(json, "$.shortUrl")).isEqualTo("http://localhost:8080/" + code);
        assertThat((Boolean) JsonPath.read(json, "$.active")).isTrue();
        mvc.perform(get("/" + code)).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/landing?a=1"));
    }

    @Test
    void createWithCustomAlias() throws Exception {
        String alias = "alias-" + System.nanoTime();
        String json = createLink(alice, "{\"longUrl\":\"https://example.com\",\"alias\":\"%s\"}".formatted(alias));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo(alias);
        assertThat((Boolean) JsonPath.read(json, "$.custom")).isTrue();

        mvc.perform(as(bob, post("/api/links")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.org\",\"alias\":\"%s\"}".formatted(alias)))
                .andExpect(status().isConflict());
    }

    /** What a browser actually does: keep the cookies the server sends, and send them back on the next call. */
    @Test
    void theCsrfCookieSurvivesRepeatedAuthenticatedWrites() throws Exception {
        MvcResult bootstrap = mvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent()).andReturn();
        String token = bootstrap.getResponse().getCookie("XSRF-TOKEN").getValue();
        Cookie xsrf = new Cookie("XSRF-TOKEN", token);

        Cookie session = mvc.perform(post("/api/auth/register").cookie(xsrf).header("X-XSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"real%d@example.com\",\"password\":\"correct horse battery\"}".formatted(System.nanoTime())))
                .andExpect(status().isCreated()).andReturn().getResponse().getCookie("access_token");

        for (int i = 0; i < 4; i++) {
            MvcResult write = mvc.perform(post("/api/links").cookie(xsrf, session).header("X-XSRF-TOKEN", token)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"longUrl\":\"https://example.com/" + i + "\"}"))
                    .andExpect(status().isCreated()).andReturn();
            // The server must never tell the browser to drop or replace the token under it.
            assertThat(write.getResponse().getHeaders("Set-Cookie")).noneMatch(h -> h.startsWith("XSRF-TOKEN="));
        }
        mvc.perform(delete("/api/links/1").cookie(xsrf, session).header("X-XSRF-TOKEN", token))
                .andExpect(status().isNotFound()); // reached the controller: CSRF still accepted
    }

    @Test
    void validationFailuresNameTheOffendingFields() throws Exception {
        mvc.perform(as(alice, post("/api/links")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"\",\"alias\":\"" + "x".repeat(40) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.errors.longUrl").isNotEmpty())
                .andExpect(jsonPath("$.errors.alias").isNotEmpty());
    }

    @Test
    void aliasWithTheGeneratedPrefixIsRejected() throws Exception {
        mvc.perform(as(alice, post("/api/links")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com\",\"alias\":\"linkly-fake1\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidInputGetsProblemJson400() throws Exception {
        for (String body : new String[] {
                "{}",
                "{\"longUrl\":\"\"}",
                "{\"longUrl\":\"javascript:alert(1)\"}",
                "{\"longUrl\":\"http://169.254.169.254/latest\"}",
                "{\"longUrl\":\"https://example.com\",\"alias\":\"a b\"}",
                "{\"longUrl\":\"https://example.com\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}"}) {
            mvc.perform(as(alice, post("/api/links")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Content-Type", "application/problem+json"));
        }
    }

    @Test
    void listIsPaginatedNewestFirstAndOnlyShowsYourOwnLinks() throws Exception {
        String first = createLink(alice, "{\"longUrl\":\"https://example.com/1\"}");
        createLink(bob, "{\"longUrl\":\"https://example.com/bobs\"}");
        String second = createLink(alice, "{\"longUrl\":\"https://example.com/2\"}");

        mvc.perform(as(alice, get("/api/links")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].code").value((String) JsonPath.read(second, "$.code")))
                .andExpect(jsonPath("$.items[1].code").value((String) JsonPath.read(first, "$.code")));

        mvc.perform(as(alice, get("/api/links?size=1&page=1")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void pageSizeIsBounded() throws Exception {
        mvc.perform(as(alice, get("/api/links?size=1000"))).andExpect(status().isBadRequest());
        mvc.perform(as(alice, get("/api/links?page=-1"))).andExpect(status().isBadRequest());
    }

    @Test
    void otherUsersCannotReadOrDeleteYourLink() throws Exception {
        String json = createLink(alice, "{\"longUrl\":\"https://example.com/private\"}");
        int id = JsonPath.read(json, "$.id");
        String code = JsonPath.read(json, "$.code");

        mvc.perform(as(bob, get("/api/links/" + id))).andExpect(status().isNotFound());
        mvc.perform(as(bob, delete("/api/links/" + id)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/" + code)).andExpect(status().isFound()); // untouched

        mvc.perform(as(alice, get("/api/links/" + id))).andExpect(status().isOk());
    }

    @Test
    void deleteDeactivatesTheLink() throws Exception {
        String json = createLink(alice, "{\"longUrl\":\"https://example.com/gone\"}");
        int id = JsonPath.read(json, "$.id");
        String code = JsonPath.read(json, "$.code");

        mvc.perform(as(alice, delete("/api/links/" + id)).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(get("/" + code)).andExpect(status().isGone());
        mvc.perform(as(alice, get("/api/links/" + id))).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void deleteWithoutCsrfTokenIsForbidden() throws Exception {
        String json = createLink(alice, "{\"longUrl\":\"https://example.com/x\"}");
        int id = JsonPath.read(json, "$.id");
        mvc.perform(as(alice, delete("/api/links/" + id))).andExpect(status().isForbidden());
    }

    @Test
    void redirectIgnoresAnyAuthCookie() throws Exception {
        String json = createLink(alice, "{\"longUrl\":\"https://example.com/public\"}");
        String code = JsonPath.read(json, "$.code");
        // A stale/garbage credential must never break a public short link.
        mvc.perform(get("/" + code).cookie(new Cookie("access_token", "garbage"))).andExpect(status().isFound());
    }
}
