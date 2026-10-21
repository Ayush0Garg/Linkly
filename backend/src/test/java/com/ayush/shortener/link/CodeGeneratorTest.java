package com.ayush.shortener.link;

import java.util.HashSet;
import java.util.Set;

import com.ayush.shortener.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGeneratorTest {

    private static final String ALPHABET = "k3G7QAe51FCsPW92uEOyq4B6jtDnRMdVXcHLzmgYZNpoIlw8TShxbaifvJU0Kr";

    private final CodeGenerator generator =
            new CodeGenerator(new AppProperties("https://sho.rt", new AppProperties.Code(ALPHABET, 6, "linkly-"), null, null));

    @Test
    void producesUniqueRedirectSafeCodesOfMinimumLength() {
        Set<String> seen = new HashSet<>();
        for (long id = 1000; id < 201_000; id++) {
            String code = generator.encode(id);
            assertThat(code).startsWith("linkly-").hasSizeGreaterThanOrEqualTo(7 + 6)
                    .matches(ReservedCodes.VALID_CODE.pattern());
            assertThat(seen.add(code)).as("duplicate code for id %d", id).isTrue();
        }
    }

    @Test
    void reservesThePrefixForGeneratedCodesCaseInsensitively() {
        assertThat(generator.usesReservedPrefix("linkly-anything")).isTrue();
        assertThat(generator.usesReservedPrefix("LINKLY-Anything")).isTrue();
        assertThat(generator.usesReservedPrefix("linkly")).isFalse();      // no dash: not our format
        assertThat(generator.usesReservedPrefix("my-linkly-page")).isFalse();
    }

    @Test
    void worksWithoutAPrefix() {
        CodeGenerator plain = new CodeGenerator(new AppProperties("https://sho.rt", new AppProperties.Code(ALPHABET, 6, ""), null, null));
        assertThat(plain.encode(1000)).hasSize(6);
        assertThat(plain.usesReservedPrefix("anything")).isFalse();
    }

    @Test
    void refusesAPrefixThatIsNotUrlSafeOrTooLong() {
        assertThatThrownBy(() -> new CodeGenerator(new AppProperties("https://sho.rt", new AppProperties.Code(ALPHABET, 6, "a/b"), null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CodeGenerator(new AppProperties("https://sho.rt", new AppProperties.Code(ALPHABET, 6, "x".repeat(30)), null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isDeterministic() {
        assertThat(generator.encode(123_456L)).isEqualTo(generator.encode(123_456L));
    }

    @Test
    void consecutiveIdsDoNotLookSequential() {
        assertThat(generator.encode(1000)).isNotEqualTo(generator.encode(1001));
        // Not a strict guarantee, but adjacent ids should differ in more than the last character.
        assertThat(generator.encode(1000).substring(7, 10)).isNotEqualTo(generator.encode(1001).substring(7, 10));
    }

    @Test
    void anAlphabetThatWasNeverProvidedFailsWithAClearMessage() {
        for (String missing : new String[] {null, "", "  ", "${CODE_ALPHABET}"}) {
            assertThatThrownBy(() -> new CodeGenerator(new AppProperties("https://sho.rt", new AppProperties.Code(missing, 6, "linkly-"), null, null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CODE_ALPHABET");
        }
    }
}
