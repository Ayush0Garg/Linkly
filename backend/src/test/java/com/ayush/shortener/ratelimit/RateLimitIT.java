package com.ayush.shortener.ratelimit;

import java.time.Instant;

import com.ayush.shortener.analytics.ClickRecorder;
import com.ayush.shortener.auth.JwtService;
import com.ayush.shortener.link.CreateLinkCommand;
import com.ayush.shortener.link.Link;
import com.ayush.shortener.link.LinkService;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static com.ayush.shortener.auth.CsrfTestSupport.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rate limits through the whole app, with Redis OFF, so the tighter per-instance limits are the ones in force:
 * sign-in/register 5 per address, sign-in 3 per account, link creation 4, QR 3, redirects 3 (the last three lowered here
 * to keep the tests quick).
 */
@SpringBootTest(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.policies.links-create.degraded-limit=4",
        "app.rate-limit.policies.qr.degraded-limit=3",
        "app.rate-limit.policies.redirect.degraded-limit=3"})
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
@ExtendWith(OutputCaptureExtension.class)
class RateLimitIT {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired LinkService links;
    @Autowired JwtService jwt;
    @Autowired ClickRecorder recorder;
    @Autowired JdbcTemplate jdbc;

    static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    MvcResult login(String email, String address) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).with(from(address)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"not the password\"}".formatted(email))).andReturn();
    }

    MvcResult register(String address) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf()).with(from(address)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"rl%d@example.com\",\"password\":\"correct horse battery\"}".formatted(System.nanoTime()))).andReturn();
    }

    static String unique(String prefix) {
        return prefix + System.nanoTime() + "@example.com";
    }

    long newUser() {
        return users.save(new User("sub-" + System.nanoTime(), unique("u"), "U", null, Instant.now())).getId();
    }

    Cookie auth(long userId) {
        return new Cookie("access_token", jwt.issueAccessToken(userId));
    }

    // ---- the 429 itself

    @Test
    void theBannerListsTheTighterLimitsAndSaysRedisIsOff(CapturedOutput output) {
        assertThat(output.getAll())
                .contains("REDIS IS OFF")
                .contains("rate limits are counted per instance only, and tightened to compensate")
                .contains("sign-in/register 5/min (normally 10) per address")
                .contains("sign-in 3/min (normally 5) per account");
    }

    @Test
    void signInIsLimitedPerAddressAndTheRefusalSaysWhenToComeBack() throws Exception {
        String address = "198.51.100.10";
        for (int i = 1; i <= 5; i++) {
            assertThat(login(unique("a"), address).getResponse().getStatus()).as("attempt %d", i).isEqualTo(401);
        }
        MvcResult blocked = login(unique("a"), address);

        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getContentType()).isEqualTo("application/problem+json");
        long retryAfter = Long.parseLong(blocked.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(blocked.getResponse().getHeader("RateLimit-Limit")).isEqualTo("5");   // the tighter, degraded limit
        assertThat(blocked.getResponse().getHeader("RateLimit-Remaining")).isEqualTo("0");
        String body = blocked.getResponse().getContentAsString();
        assertThat(body).contains("Too many attempts. Try again in").contains("\"retryAfterSeconds\":" + retryAfter);
    }

    @Test
    void anotherAddressIsUnaffected() throws Exception {
        for (int i = 0; i < 6; i++) {
            login(unique("b"), "198.51.100.20");
        }
        assertThat(login(unique("b"), "198.51.100.20").getResponse().getStatus()).isEqualTo(429);
        assertThat(login(unique("b"), "198.51.100.21").getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void signInRegisterShareOneAllowancePerAddress() throws Exception {
        String address = "198.51.100.30";
        for (int i = 0; i < 5; i++) {
            login(unique("c"), address);
        }
        assertThat(register(address).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void anAccountIsProtectedWhicheverAddressTheAttemptsComeFrom() throws Exception {
        String victim = unique("victim");
        for (int i = 1; i <= 3; i++) {
            assertThat(login(victim, "203.0.113." + i).getResponse().getStatus()).as("attempt %d from its own address", i).isEqualTo(401);
        }
        MvcResult blocked = login(victim, "203.0.113.99");  // a fresh address: the per-address limit would allow it
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getContentAsString()).contains("Too many attempts");
        assertThat(login(unique("someoneelse"), "203.0.113.99").getResponse().getStatus()).isEqualTo(401); // others are fine
    }

    @Test
    void theAccountLimitIsCaseInsensitiveSoCapitalLettersDoNotBuyMoreTries() throws Exception {
        String email = unique("case");
        login(email, "203.0.113.50");
        login(email.toUpperCase(), "203.0.113.51");
        login(" " + email + " ", "203.0.113.52");
        assertThat(login(email, "203.0.113.53").getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void theRefusalDoesNotRevealWhetherTheAccountExists() throws Exception {
        String real = users.findById(newUser()).orElseThrow().getEmail();
        String ghost = "ghost" + System.nanoTime() + "@example.com";
        for (int i = 0; i < 3; i++) {
            login(real, "203.0.113." + (60 + i));
            login(ghost, "203.0.113." + (70 + i));
        }
        MvcResult forReal = login(real, "203.0.113.90");
        MvcResult forGhost = login(ghost, "203.0.113.91");

        assertThat(forReal.getResponse().getStatus()).isEqualTo(429);
        assertThat(forGhost.getResponse().getStatus()).isEqualTo(429);
        // identical except for the countdown itself
        assertThat(withoutCountdown(forGhost)).isEqualTo(withoutCountdown(forReal));
    }

    private static String withoutCountdown(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString().replaceAll("\\d+ seconds?", "N seconds").replaceAll("\"retryAfterSeconds\":\\d+", "\"retryAfterSeconds\":N");
    }

    // ---- what is not counted

    @Test
    void requestsThatNeverGetPastSecurityDoNotUseUpTheAllowance() throws Exception {
        String address = "198.51.100.40";
        for (int i = 0; i < 12; i++) { // no CSRF token: refused (403) before any limit is consulted
            mvc.perform(post("/api/auth/login").with(from(address)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"x@example.com\",\"password\":\"y\"}")).andExpect(status().isForbidden());
        }
        for (int i = 1; i <= 5; i++) {
            assertThat(login(unique("d"), address).getResponse().getStatus()).as("real attempt %d", i).isEqualTo(401);
        }
    }

    @Test
    void successfulSignInsCountToo() throws Exception {
        String address = "198.51.100.50";
        for (int i = 1; i <= 5; i++) {
            assertThat(register(address).getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(register(address).getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void readingIsNotLimited() throws Exception {
        long userId = newUser();
        for (int i = 0; i < 30; i++) {
            mvc.perform(get("/api/links").cookie(auth(userId))).andExpect(status().isOk());
            mvc.perform(get("/api/stats/overview").cookie(auth(userId))).andExpect(status().isOk());
        }
    }

    // ---- per signed-in user

    @Test
    void linkCreationIsLimitedPerUserAndOtherUsersAreUnaffected() throws Exception {
        Cookie busy = auth(newUser());
        for (int i = 1; i <= 4; i++) {
            mvc.perform(post("/api/links").cookie(busy).with(csrf()).with(from("198.51.100.60")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"longUrl\":\"https://example.com/" + i + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("RateLimit-Limit", "4"))
                    .andExpect(header().string("RateLimit-Remaining", String.valueOf(4 - i)));
        }
        mvc.perform(post("/api/links").cookie(busy).with(csrf()).with(from("198.51.100.60")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"longUrl\":\"https://example.com/over\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("Too many requests. Try again in")));

        // the same address, a different user: a separate allowance (counted per user, not per address)
        mvc.perform(post("/api/links").cookie(auth(newUser())).with(csrf()).with(from("198.51.100.60")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"longUrl\":\"https://example.com/other\"}")).andExpect(status().isCreated());
    }

    @Test
    void qrCodesAreLimitedPerUser() throws Exception {
        long userId = newUser();
        Link link = links.create(userId, new CreateLinkCommand("https://example.com/qr", null, null));
        Cookie cookie = auth(userId);
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/links/" + link.getId() + "/qr").cookie(cookie)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/links/" + link.getId() + "/qr").cookie(cookie)).andExpect(status().isTooManyRequests());
    }

    // ---- redirects

    @Test
    void redirectsAreLimitedPerAddressAndARefusalIsNeverCountedAsAClick() throws Exception {
        long userId = newUser();
        Link link = links.create(userId, new CreateLinkCommand("https://example.com/limited", null, null));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/" + link.getCode()).with(from("198.51.100.70"))).andExpect(status().isFound());
        }
        mvc.perform(get("/" + link.getCode()).with(from("198.51.100.70")))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(head("/" + link.getCode()).with(from("198.51.100.70"))).andExpect(status().isTooManyRequests()); // HEAD counts as well
        mvc.perform(get("/" + link.getCode()).with(from("198.51.100.71"))).andExpect(status().isFound());            // another visitor

        recorder.flush();
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(clicks + bot_clicks), 0) FROM link_daily_stats WHERE link_id = ?", Long.class, link.getId()))
                .as("3 + 1 real redirects (no User-Agent, so they count as bot clicks), and none of the refused ones").isEqualTo(4L);
    }

    @Test
    void ipv6VisitorsInOneSubnetShareAnAllowance() throws Exception {
        long userId = newUser();
        Link link = links.create(userId, new CreateLinkCommand("https://example.com/v6", null, null));
        String[] sameSubnet = {"2001:db8:aaaa:1::1", "2001:db8:aaaa:1::2", "2001:db8:aaaa:1:ffff::3"};
        for (String address : sameSubnet) {
            mvc.perform(get("/" + link.getCode()).with(from(address))).andExpect(status().isFound());
        }
        mvc.perform(get("/" + link.getCode()).with(from("2001:db8:aaaa:1::99"))).andExpect(status().isTooManyRequests());
        mvc.perform(get("/" + link.getCode()).with(from("2001:db8:aaaa:2::1"))).andExpect(status().isFound()); // a different /64
    }
}
