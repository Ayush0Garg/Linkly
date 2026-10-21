package com.ayush.shortener.auth;

import jakarta.servlet.http.Cookie;
import com.jayway.jsonpath.JsonPath;
import com.ayush.shortener.auth.GoogleIdTokenVerifier.GoogleProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static com.ayush.shortener.auth.CsrfTestSupport.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class AuthIT {

    static final String PASSWORD = "correct horse battery";

    @Autowired MockMvc mvc;
    @MockitoBean GoogleIdTokenVerifier google;

    static String uniqueEmail() {
        return "user" + System.nanoTime() + "@example.com";
    }

    MvcResult register(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"Test User\"}".formatted(email, password)))
                .andReturn();
    }

    MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    MvcResult refresh(Cookie refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refreshToken)).andReturn();
    }

    static Cookie cookie(MvcResult result, String name) {
        return result.getResponse().getCookie(name);
    }

    // ---- registration ----

    @Test
    void registerCreatesAccountAndSetsHttpOnlyCookies() throws Exception {
        String email = uniqueEmail();
        MvcResult result = register(email.toUpperCase(), PASSWORD);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.email")).isEqualTo(email);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("password");

        Cookie access = cookie(result, "access_token");
        Cookie refresh = cookie(result, "refresh_token");
        assertThat(access).isNotNull();
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(access.getPath()).isEqualTo("/api");
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/auth");
    }

    @Test
    void invalidFieldsAreReportedPerField() throws Exception {
        MvcResult result = register("not-an-email", "short");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String body = result.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.errors.email")).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.errors.password")).isNotBlank();
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        assertThat(register(email.toUpperCase(), PASSWORD).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void weakOrOversizedPasswordsAndBadEmailsAreRejected() throws Exception {
        assertThat(register(uniqueEmail(), "short").getResponse().getStatus()).isEqualTo(400);
        assertThat(register(uniqueEmail(), "é".repeat(40)).getResponse().getStatus()).isEqualTo(400); // 80 bytes > bcrypt's 72
        assertThat(register("not-an-email", PASSWORD).getResponse().getStatus()).isEqualTo(400);
    }

    // ---- login / session ----

    @Test
    void loginThenMeWorksWithTheCookie() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        MvcResult result = login(email, PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        mvc.perform(get("/api/auth/me").cookie(cookie(result, "access_token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.name").value("Test User"));
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameResponse() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        MvcResult wrongPassword = login(email, "totally wrong password");
        MvcResult unknownUser = login(uniqueEmail(), PASSWORD);

        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownUser.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getResponse().getContentAsString()).isEqualTo(unknownUser.getResponse().getContentAsString());
        assertThat(wrongPassword.getResponse().getCookies()).isEmpty();
    }

    @Test
    void overlongPasswordOnLoginIsJustAFailedLogin() throws Exception {
        assertThat(login(uniqueEmail(), "x".repeat(100)).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void meRequiresAValidAccessCookie() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(new Cookie("access_token", "garbage"))).andExpect(status().isUnauthorized());
    }

    @Test
    void aStaleAccessCookieDoesNotBreakLogin() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);
        mvc.perform(post("/api/auth/login").with(csrf()).cookie(new Cookie("access_token", "expired.or.garbage"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk());
    }

    // ---- refresh rotation ----

    @Test
    void refreshRotatesTheTokenAndIssuesANewAccessToken() throws Exception {
        MvcResult first = register(uniqueEmail(), PASSWORD);
        Cookie oldRefresh = cookie(first, "refresh_token");

        MvcResult second = refresh(oldRefresh);

        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(cookie(second, "refresh_token").getValue()).isNotEqualTo(oldRefresh.getValue());
        mvc.perform(get("/api/auth/me").cookie(cookie(second, "access_token"))).andExpect(status().isOk());
    }

    @Test
    void reusingASpentRefreshTokenRevokesTheWholeFamily() throws Exception {
        MvcResult first = register(uniqueEmail(), PASSWORD);
        Cookie original = cookie(first, "refresh_token");
        Cookie rotated = cookie(refresh(original), "refresh_token");

        // The attacker (or a stale tab) replays the original token...
        MvcResult replay = refresh(original);
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(cookie(replay, "refresh_token").getMaxAge()).isZero(); // browser told to drop it

        // ...which also kills the legitimate, newer token.
        assertThat(refresh(rotated).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void refreshWithoutOrWithBogusTokenIs401() throws Exception {
        mvc.perform(post("/api/auth/refresh").with(csrf())).andExpect(status().isUnauthorized());
        assertThat(refresh(new Cookie("refresh_token", "bogus")).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheSessionAndClearsCookies() throws Exception {
        MvcResult session = register(uniqueEmail(), PASSWORD);
        Cookie refreshToken = cookie(session, "refresh_token");

        MvcResult logout = mvc.perform(post("/api/auth/logout").with(csrf()).cookie(refreshToken))
                .andExpect(status().isNoContent()).andReturn();

        assertThat(cookie(logout, "refresh_token").getMaxAge()).isZero();
        assertThat(cookie(logout, "access_token").getMaxAge()).isZero();
        assertThat(refresh(refreshToken).getResponse().getStatus()).isEqualTo(401);
    }

    // ---- CSRF ----

    @Test
    void mutatingRequestsWithoutACsrfTokenAreForbidden() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"whatever\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRealCsrfFlowWorksWithCookieAndHeader() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD);

        MvcResult bootstrap = mvc.perform(get("/api/auth/csrf")).andExpect(status().isNoContent()).andReturn();
        Cookie xsrf = cookie(bootstrap, "XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.isHttpOnly()).isFalse(); // the SPA has to be able to read it

        mvc.perform(post("/api/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", "wrong-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isForbidden());
    }

    // ---- Google ----

    MvcResult googleLogin() throws Exception {
        return mvc.perform(post("/api/auth/google").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idToken\":\"fake-token\"}")).andReturn();
    }

    @Test
    void googleSignInCreatesAnAccountAndIsStableAcrossLogins() throws Exception {
        String sub = "sub-" + System.nanoTime();
        String email = uniqueEmail();
        when(google.verify("fake-token")).thenReturn(new GoogleProfile(sub, email, "G User", "https://pic"));

        MvcResult first = googleLogin();
        MvcResult second = googleLogin();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(cookie(first, "access_token")).isNotNull();
        int id1 = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        int id2 = JsonPath.read(second.getResponse().getContentAsString(), "$.id");
        assertThat(id1).isEqualTo(id2);
        assertThat(JsonPath.<String>read(first.getResponse().getContentAsString(), "$.avatarUrl")).isEqualTo("https://pic");
    }

    @Test
    void googleSignInOnAnExistingPasswordAccountLinksItAndDropsThePassword() throws Exception {
        String email = uniqueEmail();
        MvcResult original = register(email, PASSWORD);
        Cookie oldRefresh = cookie(original, "refresh_token");
        when(google.verify("fake-token")).thenReturn(new GoogleProfile("sub-" + System.nanoTime(), email, "G User", null));

        MvcResult linked = googleLogin();

        assertThat(linked.getResponse().getStatus()).isEqualTo(200);
        int originalId = JsonPath.read(original.getResponse().getContentAsString(), "$.id");
        assertThat((int) JsonPath.read(linked.getResponse().getContentAsString(), "$.id")).isEqualTo(originalId);
        // Whoever knew the old password (possibly a squatter) is locked out, and so are their sessions.
        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(oldRefresh).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void passwordLoginOnAGoogleOnlyAccountFailsGenerically() throws Exception {
        String email = uniqueEmail();
        when(google.verify("fake-token")).thenReturn(new GoogleProfile("sub-" + System.nanoTime(), email, "G", null));
        googleLogin();

        assertThat(login(email, PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(register(email, PASSWORD).getResponse().getStatus()).isEqualTo(409);
    }
}
