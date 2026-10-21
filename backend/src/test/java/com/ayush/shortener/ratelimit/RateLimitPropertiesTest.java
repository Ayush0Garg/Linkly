package com.ayush.shortener.ratelimit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Binds configuration the way the application does, because a record's defaults are easy to get wrong. */
class RateLimitPropertiesTest {

    private static Map<String, String> config() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("app.rate-limit.enabled", "true");
        for (String name : Policies.ALL) {
            m.put("app.rate-limit.policies." + name + ".limit", "10");
            m.put("app.rate-limit.policies." + name + ".per", "1m");
            m.put("app.rate-limit.policies." + name + ".degraded-limit", "5");
        }
        return m;
    }

    private static RateLimitProperties bind(Map<String, String> config) {
        return new Binder(new MapConfigurationPropertySource(config)).bind("app.rate-limit", RateLimitProperties.class).get();
    }

    @Test
    void aPolicyThatSaysNothingAboutItIsCountedInRedisSharedByAllInstances() {
        RateLimitProperties props = bind(config());
        for (String name : Policies.ALL) {
            assertThat(props.policy(name).shared()).as("%s is shared by default", name).isTrue();
        }
    }

    @Test
    void localOnlyOptsAPolicyOutOfRedis() {
        Map<String, String> config = config();
        config.put("app.rate-limit.policies.redirect.local-only", "true");
        RateLimitProperties props = bind(config);
        assertThat(props.policy(Policies.REDIRECT).shared()).isFalse();
        assertThat(props.policy(Policies.AUTH_IP).shared()).isTrue();
    }

    @Test
    void numbersAndDurationsBind() {
        RateLimitProperties.Policy policy = bind(config()).policy(Policies.QR);
        assertThat(policy.limit()).isEqualTo(10);
        assertThat(policy.degradedLimit()).isEqualTo(5);
        assertThat(policy.per()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void theShippedDefaultsAreAsAgreedAndTheDegradedOnesAreTighter() throws Exception {
        var yaml = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml")).get(0);
        var props = new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(yaml))
                .bind("app.rate-limit", RateLimitProperties.class).get();
        assertThat(props.enabled()).isTrue();
        assertThat(props.policy(Policies.AUTH_IP).limit()).isEqualTo(10);
        assertThat(props.policy(Policies.AUTH_EMAIL).limit()).isEqualTo(5);
        assertThat(props.policy(Policies.LINKS_CREATE).limit()).isEqualTo(30);
        assertThat(props.policy(Policies.QR).limit()).isEqualTo(60);
        assertThat(props.policy(Policies.REDIRECT).limit()).isEqualTo(600);
        for (String name : Policies.ALL) {
            assertThat(props.policy(name).degradedLimit()).as("%s degraded is tighter", name).isLessThan(props.policy(name).limit());
        }
        // only redirects stay per instance
        for (String name : Policies.ALL) {
            assertThat(props.policy(name).shared()).as(name).isEqualTo(!name.equals(Policies.REDIRECT));
        }
    }

    @Test
    void aMissingPolicyFailsTheStartInsteadOfSilentlyNotLimiting() {
        Map<String, String> config = config();
        config.keySet().removeIf(k -> k.contains(".qr."));
        assertThatThrownBy(() -> bind(config)).hasRootCauseMessage("app.rate-limit.policies.qr must define limit, per and degraded-limit");
    }
}
