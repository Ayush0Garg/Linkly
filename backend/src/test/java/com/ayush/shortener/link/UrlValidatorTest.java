package com.ayush.shortener.link;

import com.ayush.shortener.common.ApiException;
import com.ayush.shortener.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator =
            new UrlValidator(new AppProperties("https://sho.rt", new AppProperties.Code("abc", 6, "linkly-"), null, null));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1&r=2#frag",
            "  https://example.com/trimmed  ",
            "HTTPS://Example.COM/Upper",
            "https://sub.domain.example.co.uk:8443/a",
            "http://8.8.8.8/dns",
            "http://[2001:4860:4860::8888]/"
    })
    void acceptsNormalPublicUrls(String url) {
        assertThat(validator.validate(url)).isNotBlank();
    }

    @Test
    void trimsWhitespace() {
        assertThat(validator.validate("  https://example.com/x  ")).isEqualTo("https://example.com/x");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "not a url",
            "example.com",
            "ftp://example.com/file",
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "https://",
            "https://user:pass@example.com",
            "https://trusted.com@evil.com",
            "https://sho.rt/abc123",
            "http://localhost:8080",
            "http://app.localhost",
            "http://intranet",
            "http://printer.local",
            "http://service.internal",
            "http://127.0.0.1",
            "http://10.0.0.5",
            "http://172.16.3.4",
            "http://192.168.1.1",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1",
            "http://0.0.0.0",
            "http://2130706433",
            "http://0x7f.0.0.1",
            "http://127.1",
            "http://010.0.0.1",
            "http://[::1]/",
            "http://[fd00::1]/",
            "http://[::ffff:127.0.0.1]/"
    })
    void rejectsUnsafeOrMalformedUrls(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsUrlsOfExactlyTheMaximumLength() {
        String url = "https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH - "https://example.com/".length());
        assertThat(validator.validate(url)).hasSize(UrlValidator.MAX_LENGTH);
    }

    @Test
    void rejectsUrlsThatOnlyExceedTheLimitAfterPercentEncoding() {
        String url = "https://example.com/" + "日".repeat(1000); // ~1,000 characters as typed, ~9,000 once encoded
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsShortNonAsciiUrlsAndStoresThemEncoded() {
        assertThat(validator.validate("https://example.com/café")).isEqualTo("https://example.com/caf%C3%A9");
    }

    @Test
    void rejectsOverlongUrls() {
        String url = "https://example.com/" + "a".repeat(UrlValidator.MAX_LENGTH);
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(ApiException.class);
    }
}
