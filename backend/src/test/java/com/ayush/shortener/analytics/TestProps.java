package com.ayush.shortener.analytics;

import java.time.Duration;

import com.ayush.shortener.config.AppProperties;

final class TestProps {

    private TestProps() {
    }

    static AppProperties props(int queueCapacity, int batchSize) {
        return new AppProperties("https://sho.rt", null, null,
                new AppProperties.Analytics(queueCapacity, batchSize, Duration.ofSeconds(1), "unit-test-salt-0123456789", null, 365));
    }
}
