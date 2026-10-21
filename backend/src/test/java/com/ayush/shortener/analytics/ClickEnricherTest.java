package com.ayush.shortener.analytics;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClickEnricherTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://www.google.com/search?q=secret+token|google.com",
            "https://t.co/AbCdEf|t.co",
            "http://News.Example.COM:8080/a/b?c=d#e|news.example.com",
            "android-app://com.google.android.gm|com.google.android.gm"
    })
    void keepsOnlyTheHostOfTheReferrer(String referer, String host) {
        assertThat(ClickEnricher.referrerHost(referer)).isEqualTo(host);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not a url", "///", "http://", "mailto:someone@example.com"})
    void unusableReferrersBecomeNull(String referer) {
        assertThat(ClickEnricher.referrerHost(referer)).isNull();
    }

    ClickEnricher enricher() {
        return new ClickEnricher(new UserAgentClassifier(),
                ip -> "8.8.8.8".equals(ip) ? Optional.of("US") : Optional.empty(), new VisitorHasher(TestProps.props(10, 10)));
    }

    static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Test
    void captureReducesTheIpToACountryAndAHashAndKeepsNothingElseAboutIt() {
        Instant at = Instant.parse("2026-10-14T08:00:00Z");
        PendingClick click = enricher().capture(42, at, "8.8.8.8", CHROME, "https://www.reddit.com/r/java/comments/abc");

        assertThat(click.linkId()).isEqualTo(42);
        assertThat(click.at()).isEqualTo(at);
        assertThat(click.country()).isEqualTo("US");
        assertThat(click.referrerHost()).isEqualTo("reddit.com");
        assertThat(click.visitorHash()).hasSize(32).doesNotContain("8.8.8.8");
        assertThat(click.userAgent()).isEqualTo(CHROME);
        assertThat(click.eventId()).isNotNull();
        // nothing that travels to Redis or memory can contain the address
        assertThat(click.toString()).doesNotContain("8.8.8.8");
        assertThat(click.toFields().values()).noneMatch(v -> v.contains("8.8.8.8"));
    }

    @Test
    void everyClickGetsItsOwnEventId() {
        ClickEnricher enricher = enricher();
        Instant at = Instant.now();
        assertThat(enricher.capture(1, at, "8.8.8.8", CHROME, null).eventId())
                .isNotEqualTo(enricher.capture(1, at, "8.8.8.8", CHROME, null).eventId());
    }

    @Test
    void captureKeepsTheUserAgentBounded() {
        assertThat(enricher().capture(1, Instant.now(), "8.8.8.8", "x".repeat(5_000), null).userAgent()).hasSize(512);
        assertThat(enricher().capture(1, Instant.now(), "8.8.8.8", null, null).userAgent()).isNull();
    }

    @Test
    void finishParsesTheUserAgentIntoTheStoredRowAndKeepsTheEventId() {
        ClickEnricher enricher = enricher();
        PendingClick click = enricher.capture(7, Instant.parse("2026-10-14T08:00:00Z"), "8.8.8.8", CHROME, "https://t.co/x");
        ClickRow row = enricher.finish(click);

        assertThat(row.eventId()).isEqualTo(click.eventId());
        assertThat(row.linkId()).isEqualTo(7);
        assertThat(row.country()).isEqualTo("US");
        assertThat(row.device()).isEqualTo("desktop");
        assertThat(row.browser()).isEqualTo("Chrome");
        assertThat(row.os()).isEqualTo("Windows");
        assertThat(row.referrerHost()).isEqualTo("t.co");
        assertThat(row.visitorHash()).isEqualTo(click.visitorHash());
        assertThat(row.bot()).isFalse();
    }

    @Test
    void unknownCountryAndMissingUserAgentAreHandled() {
        ClickEnricher enricher = enricher();
        ClickRow row = enricher.finish(enricher.capture(1, Instant.now(), "10.0.0.1", null, null));
        assertThat(row.country()).isNull();
        assertThat(row.bot()).isTrue(); // no User-Agent: a script
    }
}
