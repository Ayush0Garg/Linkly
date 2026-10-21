package com.ayush.shortener.analytics;

import java.time.Instant;

import com.ayush.shortener.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisitorHasherTest {

    private final VisitorHasher hasher = new VisitorHasher(TestProps.props(10, 10));
    private static final Instant MONDAY = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant MONDAY_LATE = Instant.parse("2026-10-12T23:59:59Z");
    private static final Instant TUESDAY = Instant.parse("2026-10-13T00:00:01Z");

    @Test
    void sameVisitorSameDayGetsTheSameHash() {
        assertThat(hasher.hash("203.0.113.9", "UA-1", MONDAY)).isEqualTo(hasher.hash("203.0.113.9", "UA-1", MONDAY_LATE));
    }

    @Test
    void differentIpOrBrowserGetsADifferentHash() {
        String base = hasher.hash("203.0.113.9", "UA-1", MONDAY);
        assertThat(hasher.hash("203.0.113.10", "UA-1", MONDAY)).isNotEqualTo(base);
        assertThat(hasher.hash("203.0.113.9", "UA-2", MONDAY)).isNotEqualTo(base);
    }

    @Test
    void theSameVisitorCannotBeLinkedAcrossDays() {
        assertThat(hasher.hash("203.0.113.9", "UA-1", MONDAY)).isNotEqualTo(hasher.hash("203.0.113.9", "UA-1", TUESDAY));
    }

    @Test
    void isFixedLengthHexAndDoesNotContainTheIp() {
        String hash = hasher.hash("203.0.113.9", "UA-1", MONDAY);
        assertThat(hash).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(hash).doesNotContain("203");
    }

    @Test
    void aDifferentSaltGivesDifferentHashes() {
        AppProperties other = new AppProperties("u", null, null, new AppProperties.Analytics(
                1, 1, java.time.Duration.ofSeconds(1), "a-completely-different-salt", null, 1));
        assertThat(new VisitorHasher(other).hash("1.2.3.4", "UA", MONDAY)).isNotEqualTo(hasher.hash("1.2.3.4", "UA", MONDAY));
    }

    @Test
    void toleratesNullInputs() {
        assertThat(hasher.hash(null, null, MONDAY)).hasSize(32);
    }

    @Test
    void refusesToStartWithAMissingOrShortSalt() {
        for (String salt : new String[] {null, "", "short"}) {
            AppProperties bad = new AppProperties("u", null, null, new AppProperties.Analytics(
                    1, 1, java.time.Duration.ofSeconds(1), salt, null, 1));
            assertThatThrownBy(() -> new VisitorHasher(bad)).isInstanceOf(IllegalStateException.class);
        }
    }
}
