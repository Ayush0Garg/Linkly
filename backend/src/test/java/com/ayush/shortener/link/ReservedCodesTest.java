package com.ayush.shortener.link;

import java.util.Locale;

import com.ayush.shortener.config.AppProperties;
import com.ayush.shortener.web.SpaRoutes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservedCodesTest {

    private static final String ALPHABET = "k3G7QAe51FCsPW92uEOyq4B6jtDnRMdVXcHLzmgYZNpoIlw8TShxbaifvJU0Kr";

    @Test
    void theWebAppsPagesCannotBeUsedAsShortCodes() {
        for (String segment : new String[] {"login", "register", "links"}) {
            assertThat(ReservedCodes.isReserved(segment)).as(segment).isTrue();
            assertThat(ReservedCodes.isReserved(segment.toUpperCase(Locale.ROOT))).as(segment + " in capitals").isTrue();
            assertThat(ReservedCodes.isReserved(Character.toUpperCase(segment.charAt(0)) + segment.substring(1))).isTrue();
        }
        SpaRoutes.TOP_LEVEL_SEGMENTS.forEach(s -> assertThat(ReservedCodes.isReserved(s)).as(s).isTrue());
        assertThat(ReservedCodes.isReserved("assets")).isTrue();
    }

    @Test
    void ordinaryWordsAreStillAllowed() {
        assertThat(ReservedCodes.isReserved("linkedin")).isFalse();
        assertThat(ReservedCodes.isReserved("my-links")).isFalse();
        assertThat(ReservedCodes.isReserved("registered")).isFalse();
    }

    @Test
    void aGeneratedCodeCanNeverEqualAPageBecauseAllOfThemCarryTheBrandPrefix() {
        CodeGenerator generator =
                new CodeGenerator(new AppProperties("https://sho.rt", new AppProperties.Code(ALPHABET, 6, "linkly-"), null, null));
        // the guarantee: no reserved word starts with the prefix, and every generated code does
        for (String reserved : SpaRoutes.TOP_LEVEL_SEGMENTS) {
            assertThat(generator.usesReservedPrefix(reserved)).as(reserved).isFalse();
        }
        for (long id = 1; id < 50_000; id++) {
            assertThat(ReservedCodes.isReserved(generator.encode(id))).as("id " + id).isFalse();
        }
        for (long id : new long[] {Long.MAX_VALUE, Long.MAX_VALUE / 3, 1L << 40, 999_999_999_999L}) {
            String code = generator.encode(id);
            assertThat(code).startsWith("linkly-");
            assertThat(ReservedCodes.isReserved(code)).isFalse();
        }
    }
}
