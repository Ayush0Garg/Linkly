package com.ayush.shortener.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentClassifierTest {

    private final UserAgentClassifier classifier = new UserAgentClassifier();

    @ParameterizedTest(name = "{1}/{2}/{3}")
    @CsvSource(delimiter = '|', value = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36|Chrome|Windows|desktop",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15|Safari|macOS|desktop",
            "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0|Firefox|Linux|desktop",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0|Edge|Windows|desktop",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1|Safari|iOS|mobile",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36|Chrome|Android|mobile",
            "Mozilla/5.0 (iPad; CPU OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1|Safari|iOS|tablet",
            "Mozilla/5.0 (Linux; Android 13; SM-X700) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36|Chrome|Android|tablet"
    })
    void classifiesRealBrowsers(String ua, String browser, String os, String device) {
        UserAgentClassifier.Result result = classifier.classify(ua);
        assertThat(result.browser()).isEqualTo(browser);
        assertThat(result.os()).isEqualTo(os);
        assertThat(result.device()).isEqualTo(device);
        assertThat(result.bot()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "WhatsApp/2.23.20 A",
            "curl/8.4.0",
            "python-requests/2.31.0",
            "Wget/1.21.4",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/120.0.0.0 Safari/537.36",
            "Go-http-client/2.0"
    })
    void flagsBotsCrawlersAndScripts(String ua) {
        UserAgentClassifier.Result result = classifier.classify(ua);
        assertThat(result.bot()).as(ua).isTrue();
        assertThat(result.device()).isEqualTo("bot");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void aMissingUserAgentIsTreatedAsAScript(String ua) {
        assertThat(classifier.classify(ua).bot()).isTrue();
    }

    @Test
    void unknownAgentsDegradeToNullDimensionsNotErrors() {
        UserAgentClassifier.Result result = classifier.classify("TotallyMadeUpClient/9.9");
        assertThat(result.device()).isEqualTo("desktop");
        assertThat(result.browser()).isNull();
        assertThat(result.os()).isNull();
    }

    @Test
    void survivesAHugeUserAgent() {
        assertThat(classifier.classify("Mozilla/5.0 " + "x".repeat(100_000))).isNotNull();
    }

    @Test
    void repeatedUserAgentsAreAnsweredFromMemoryWithTheSameResult() {
        String ua = "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0";
        UserAgentClassifier.Result first = classifier.classify(ua);

        assertThat(classifier.classify(ua)).isEqualTo(first).isSameAs(classifier.classify(ua));
        assertThat(classifier.cachedEntries()).isEqualTo(1);
    }

    @Test
    void aClientSendingEndlessUniqueUserAgentsCannotGrowTheCacheWithoutBound() {
        for (int i = 0; i < UserAgentClassifier.CACHE_LIMIT * 3; i++) {
            classifier.classify("Mozilla/5.0 (X11; Linux x86_64) Unique/" + i);
        }

        assertThat(classifier.cachedEntries()).isLessThanOrEqualTo(UserAgentClassifier.CACHE_LIMIT);
        // and it still answers correctly after being restarted
        assertThat(classifier.classify("Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0").browser())
                .isEqualTo("Firefox");
    }
}
