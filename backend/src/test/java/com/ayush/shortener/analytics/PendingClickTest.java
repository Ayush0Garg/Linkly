package com.ayush.shortener.analytics;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The shape of a click inside the Redis stream. */
class PendingClickTest {

    static PendingClick sample() {
        return new PendingClick(UUID.randomUUID(), 1042, Instant.ofEpochMilli(1_791_965_354_081L), "IN",
                "0123456789abcdef0123456789abcdef", "Mozilla/5.0 (X11; Linux x86_64)", "news.ycombinator.com");
    }

    @Test
    void survivesAJourneyThroughTheStream() {
        PendingClick click = sample();
        assertThat(PendingClick.fromFields(click.toFields())).isEqualTo(click);
    }

    @Test
    void unknownPartsAreSimplyAbsentAndComeBackAsNull() {
        PendingClick click = new PendingClick(UUID.randomUUID(), 5, Instant.ofEpochMilli(1_000), null, "abc", null, null);
        Map<String, String> fields = click.toFields();
        assertThat(fields).doesNotContainKeys("c", "ua", "ref");
        assertThat(PendingClick.fromFields(fields)).isEqualTo(click);
    }

    @Test
    void carriesAVersionSoTheFormatCanChangeLater() {
        assertThat(sample().toFields()).containsEntry("v", "1");
    }

    @Test
    void everyKindOfBrokenEntryIsRejectedAsUnreadableNotAsACrash() {
        Map<String, String> good = sample().toFields();
        for (String missing : new String[] {"id", "link", "at", "vh", "v"}) {
            Map<String, String> broken = new HashMap<>(good);
            broken.remove(missing);
            assertThatThrownBy(() -> PendingClick.fromFields(broken)).as("without %s", missing).isInstanceOf(IllegalArgumentException.class);
        }
        for (String[] bad : new String[][] {{"id", "not-a-uuid"}, {"link", "abc"}, {"at", "yesterday"}, {"v", "99"}, {"link", ""}}) {
            Map<String, String> broken = new HashMap<>(good);
            broken.put(bad[0], bad[1]);
            assertThatThrownBy(() -> PendingClick.fromFields(broken)).as("%s=%s", bad[0], bad[1]).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PendingClick.fromFields(Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
