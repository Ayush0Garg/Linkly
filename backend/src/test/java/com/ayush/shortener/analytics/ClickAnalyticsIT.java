package com.ayush.shortener.analytics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.ayush.shortener.auth.JwtService;
import com.ayush.shortener.link.CreateLinkCommand;
import com.ayush.shortener.link.Link;
import com.ayush.shortener.link.LinkService;
import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ClickAnalyticsIT {

    static final String CHROME_WIN = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1";
    static final String GOOGLEBOT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    @Autowired MockMvc mvc;
    @Autowired ClickRecorder recorder;
    @Autowired ClickWriter writer;
    @Autowired LinkService links;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GeoLocator geo;

    long userId;
    Cookie auth;
    Link link;

    long newUser() {
        return users.save(new User("sub-" + System.nanoTime(), "a" + System.nanoTime() + "@example.com", "A", null, Instant.now())).getId();
    }

    Cookie authFor(long id) {
        return new Cookie("access_token", jwt.issueAccessToken(id));
    }

    @BeforeEach
    void setUp() {
        userId = newUser();
        auth = authFor(userId);
        link = links.create(userId, new CreateLinkCommand("https://example.com/landing", null, null));
        when(geo.countryOf("8.8.8.8")).thenReturn(Optional.of("US"));
        when(geo.countryOf("1.1.1.1")).thenReturn(Optional.of("AU"));
    }

    void click(String code, String ip, String ua, String referer) throws Exception {
        mvc.perform(get("/" + code).with(request -> {
            request.setRemoteAddr(ip);
            if (ua != null) {
                request.addHeader("User-Agent", ua);
            }
            if (referer != null) {
                request.addHeader("Referer", referer);
            }
            return request;
        })).andExpect(status().isFound());
    }

    long rows(long linkId) {
        return jdbc.queryForObject("SELECT count(*) FROM click_events WHERE link_id = ?", Long.class, linkId);
    }

    ResultActions stats(Cookie cookie, long linkId, String query) throws Exception {
        return mvc.perform(get("/api/links/" + linkId + "/stats" + query).cookie(cookie));
    }

    // ---- capture ----

    @Test
    void aRedirectRecordsOneFullyEnrichedClickWithoutKeepingTheIp() throws Exception {
        click(link.getCode(), "8.8.8.8", CHROME_WIN, "https://www.google.com/search?q=private+stuff");
        recorder.flush();

        var row = jdbc.queryForMap("SELECT * FROM click_events WHERE link_id = ?", link.getId());
        assertThat(row).containsEntry("country", "US").containsEntry("device", "desktop")
                .containsEntry("browser", "Chrome").containsEntry("os", "Windows")
                .containsEntry("referrer_host", "google.com").containsEntry("bot", false);
        assertThat((String) row.get("visitor_hash")).hasSize(32);
        assertThat(row.values().toString()).doesNotContain("8.8.8.8").doesNotContain("private");

        assertThat(jdbc.queryForObject("SELECT clicks FROM link_daily_stats WHERE link_id = ? AND day = ?", Long.class,
                link.getId(), LocalDate.now(ZoneOffset.UTC))).isEqualTo(1);
    }

    @Test
    void headRequestsAndFailedRedirectsAreNotCounted() throws Exception {
        mvc.perform(head("/" + link.getCode())).andExpect(status().isFound()); // previewers send HEAD: must still work
        mvc.perform(get("/linkly-doesnotexist")).andExpect(status().isNotFound());
        Link dead = links.create(userId, new CreateLinkCommand("https://example.com/dead", null, null));
        links.deactivate(userId, dead.getId());
        mvc.perform(get("/" + dead.getCode())).andExpect(status().isGone());
        recorder.flush();

        assertThat(rows(link.getId())).isZero();
        assertThat(rows(dead.getId())).isZero();
    }

    @Test
    void botsAreStoredButKeptOutOfHumanNumbers() throws Exception {
        click(link.getCode(), "8.8.8.8", CHROME_WIN, null);
        click(link.getCode(), "1.1.1.1", CHROME_WIN, null);
        for (int i = 0; i < 3; i++) {
            click(link.getCode(), "8.8.8.8", GOOGLEBOT, null);
        }
        recorder.flush();

        assertThat(rows(link.getId())).isEqualTo(5);
        stats(auth, link.getId(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.clicks").value(2))
                .andExpect(jsonPath("$.totals.botClicks").value(3))
                .andExpect(jsonPath("$.totals.uniqueVisitors").value(2))
                .andExpect(jsonPath("$.devices.length()").value(1))
                .andExpect(jsonPath("$.devices[0].key").value("desktop"));
    }

    // ---- reads ----

    @Test
    void linkStatsAggregateEveryDimension() throws Exception {
        for (int i = 0; i < 3; i++) {
            click(link.getCode(), "8.8.8.8", CHROME_WIN, "https://www.google.com/search?q=x");  // one visitor, 3 clicks
        }
        click(link.getCode(), "8.8.8.8", IPHONE, "https://t.co/abc");                            // same IP, other browser
        click(link.getCode(), "1.1.1.1", CHROME_WIN, null);                                       // direct visit from AU
        recorder.flush();

        stats(auth, link.getId(), "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.totals.clicks").value(5))
                .andExpect(jsonPath("$.totals.uniqueVisitors").value(3))
                .andExpect(jsonPath("$.totals.botClicks").value(0))
                .andExpect(jsonPath("$.countries[0].key").value("US"))
                .andExpect(jsonPath("$.countries[0].count").value(4))
                .andExpect(jsonPath("$.countries[1].key").value("AU"))
                .andExpect(jsonPath("$.devices[0].key").value("desktop"))
                .andExpect(jsonPath("$.devices[0].count").value(4))
                .andExpect(jsonPath("$.devices[1].key").value("mobile"))
                .andExpect(jsonPath("$.browsers[0].key").value("Chrome"))
                .andExpect(jsonPath("$.browsers[1].key").value("Safari"))
                .andExpect(jsonPath("$.operatingSystems[0].key").value("Windows"))
                .andExpect(jsonPath("$.operatingSystems[1].key").value("iOS"))
                .andExpect(jsonPath("$.referrers[0].key").value("google.com"))
                .andExpect(jsonPath("$.referrers[0].count").value(3))
                .andExpect(jsonPath("$.referrers.length()").value(3))
                // the series covers every day of the window, oldest first, ending today
                .andExpect(jsonPath("$.timeseries.length()").value(30))
                .andExpect(jsonPath("$.timeseries[29].date").value(LocalDate.now(ZoneOffset.UTC).toString()))
                .andExpect(jsonPath("$.timeseries[29].clicks").value(5))
                .andExpect(jsonPath("$.timeseries[0].clicks").value(0));
    }

    @Test
    void unknownDimensionsAreLabelledNotDropped() throws Exception {
        click(link.getCode(), "10.9.9.9", "TotallyMadeUpClient/9.9", null); // no country, unknown browser and OS
        recorder.flush();

        stats(auth, link.getId(), "")
                .andExpect(jsonPath("$.countries[0].key").value("Unknown"))
                .andExpect(jsonPath("$.browsers[0].key").value("Unknown"))
                .andExpect(jsonPath("$.operatingSystems[0].key").value("Unknown"))
                .andExpect(jsonPath("$.referrers[0].key").value("Direct"));
    }

    @Test
    void theDaysParameterNarrowsTheWindow() throws Exception {
        Instant tenDaysAgo = Instant.now().minus(java.time.Duration.ofDays(10));
        writer.write(List.of(new ClickRow(java.util.UUID.randomUUID(), link.getId(), tenDaysAgo, "US", "desktop", "Chrome", "Windows", null,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", false)));
        click(link.getCode(), "8.8.8.8", CHROME_WIN, null);
        recorder.flush();

        stats(auth, link.getId(), "?days=30").andExpect(jsonPath("$.totals.clicks").value(2));
        stats(auth, link.getId(), "?days=7")
                .andExpect(jsonPath("$.totals.clicks").value(1))
                .andExpect(jsonPath("$.totals.uniqueVisitors").value(1))
                .andExpect(jsonPath("$.timeseries.length()").value(7));
    }

    @Test
    void emptyLinksReturnZerosAndAFullSeries() throws Exception {
        stats(auth, link.getId(), "?days=14")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.clicks").value(0))
                .andExpect(jsonPath("$.countries.length()").value(0))
                .andExpect(jsonPath("$.timeseries.length()").value(14));
    }

    @Test
    void statsBelongToTheOwnerOnly() throws Exception {
        Cookie stranger = authFor(newUser());
        stats(stranger, link.getId(), "").andExpect(status().isNotFound());
        stats(auth, link.getId() + 99_999, "").andExpect(status().isNotFound());
        mvc.perform(get("/api/links/" + link.getId() + "/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void daysIsBounded() throws Exception {
        for (String days : new String[] {"0", "-5", "91", "1000", "abc"}) {
            stats(auth, link.getId(), "?days=" + days).andExpect(status().isBadRequest());
        }
        stats(auth, link.getId(), "?days=1").andExpect(status().isOk());
        stats(auth, link.getId(), "?days=90").andExpect(status().isOk());
    }

    @Test
    void overviewCoversAllOfMyLinksAndRanksTheTopOnes() throws Exception {
        Link second = links.create(userId, new CreateLinkCommand("https://example.com/second", null, null));
        Link strangers = links.create(newUser(), new CreateLinkCommand("https://example.com/not-mine", null, null));
        for (int i = 0; i < 3; i++) {
            click(link.getCode(), "8.8.8.8", CHROME_WIN, null);
        }
        click(second.getCode(), "1.1.1.1", IPHONE, null);
        for (int i = 0; i < 5; i++) {
            click(strangers.getCode(), "8.8.8.8", CHROME_WIN, null);
        }
        recorder.flush();

        mvc.perform(get("/api/stats/overview").cookie(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.clicks").value(4))
                .andExpect(jsonPath("$.totals.uniqueVisitors").value(2))
                .andExpect(jsonPath("$.topLinks.length()").value(2))
                .andExpect(jsonPath("$.topLinks[0].linkId").value(link.getId()))
                .andExpect(jsonPath("$.topLinks[0].clicks").value(3))
                .andExpect(jsonPath("$.topLinks[0].shortUrl").value("http://localhost:8080/" + link.getCode()))
                .andExpect(jsonPath("$.topLinks[1].linkId").value(second.getId()))
                .andExpect(jsonPath("$.timeseries.length()").value(30))
                .andExpect(jsonPath("$.timeseries[29].clicks").value(4));

        mvc.perform(get("/api/stats/overview?days=0").cookie(auth)).andExpect(status().isBadRequest());
    }

    @Test
    void theLinkListShowsEachLinksTotalClicks() throws Exception {
        Link quiet = links.create(userId, new CreateLinkCommand("https://example.com/quiet", null, null));
        click(link.getCode(), "8.8.8.8", CHROME_WIN, null);
        click(link.getCode(), "1.1.1.1", CHROME_WIN, null);
        click(link.getCode(), "1.1.1.1", GOOGLEBOT, null);
        recorder.flush();

        mvc.perform(get("/api/links").cookie(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + link.getId() + ")].totalClicks").value(2))
                .andExpect(jsonPath("$.items[?(@.id == " + quiet.getId() + ")].totalClicks").value(0));
        mvc.perform(get("/api/links/" + link.getId()).cookie(auth)).andExpect(jsonPath("$.totalClicks").value(2));
    }
}
