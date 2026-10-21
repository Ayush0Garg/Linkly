package com.ayush.shortener.ratelimit;

import java.time.Duration;
import java.util.List;

import com.ayush.shortener.redis.DegradedEffects;
import org.springframework.stereotype.Component;

/** What the rate limits become while Redis is off, for the startup banner. */
@Component
class RateLimitDegradedEffects implements DegradedEffects {

    private final RateLimitProperties props;

    RateLimitDegradedEffects(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public List<String> effects() {
        if (!props.enabled()) {
            return List.of("rate limiting is switched off (app.rate-limit.enabled=false)");
        }
        return List.of("rate limits are counted per instance only, and tightened to compensate: "
                + "sign-in/register " + p(Policies.AUTH_IP) + " per address, sign-in " + p(Policies.AUTH_EMAIL) + " per account, "
                + "link creation " + p(Policies.LINKS_CREATE) + ", QR codes " + p(Policies.QR) + ", redirects " + p(Policies.REDIRECT) + " per address");
    }

    private String p(String name) {
        RateLimitProperties.Policy policy = props.policy(name);
        return policy.degradedLimit() + "/" + unit(policy.per()) + " (normally " + policy.limit() + ")";
    }

    private static String unit(Duration per) {
        if (per.equals(Duration.ofMinutes(1))) {
            return "min";
        }
        if (per.equals(Duration.ofHours(1))) {
            return "hour";
        }
        if (per.equals(Duration.ofSeconds(1))) {
            return "sec";
        }
        return per.toString();
    }
}
