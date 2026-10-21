package com.ayush.shortener.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpaMissingBuildTest {

    @Test
    void startingWithTheWebAppOnButNotBuiltInFailsFastWithAClearMessage() {
        SpaWebConfig config = new SpaWebConfig(new WebProperties(true, "classpath:/nothing-here/"), new DefaultResourceLoader());

        assertThatThrownBy(config::requireTheBuiltApp)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath:/nothing-here/index.html")
                .hasMessageContaining("Dockerfile");
    }

    @Test
    void aBuiltAppIsAccepted() {
        SpaWebConfig config = new SpaWebConfig(new WebProperties(true, "classpath:/spa-test/"), new DefaultResourceLoader());

        assertThatCode(config::requireTheBuiltApp).doesNotThrowAnyException();
    }

    @Test
    void locationsAreNormalisedWhateverTheTrailingSlash() {
        assertThatCode(() -> {
            org.assertj.core.api.Assertions.assertThat(new WebProperties(true, "file:/opt/web").indexLocation()).isEqualTo("file:/opt/web/index.html");
            org.assertj.core.api.Assertions.assertThat(new WebProperties(true, "file:/opt/web/").folder()).isEqualTo("file:/opt/web/");
        }).doesNotThrowAnyException();
    }
}
