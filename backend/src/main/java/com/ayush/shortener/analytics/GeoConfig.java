package com.ayush.shortener.analytics;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import com.ayush.shortener.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
class GeoConfig {

    private static final Logger log = LoggerFactory.getLogger(GeoConfig.class);

    @Bean(destroyMethod = "close")
    MaxMindOrNone geoLocator(AppProperties properties) throws IOException {
        String path = properties.analytics().geoipDb();
        if (!StringUtils.hasText(path)) {
            log.info("No GEOIP_DB configured: clicks will be recorded without a country");
            return new MaxMindOrNone(null);
        }
        File file = new File(path);
        if (!file.isFile()) {
            // A configured-but-missing database is a deployment mistake, not something to silently degrade on.
            throw new IllegalStateException("app.analytics.geoip-db points to a file that does not exist: " + path);
        }
        return new MaxMindOrNone(new MaxMindGeoLocator(file));
    }

    /** Wrapper so there is one GeoLocator bean whether or not a database is configured, and it closes cleanly. */
    static final class MaxMindOrNone implements GeoLocator, AutoCloseable {

        private final MaxMindGeoLocator delegate;

        MaxMindOrNone(MaxMindGeoLocator delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<String> countryOf(String ip) {
            return delegate == null ? Optional.empty() : delegate.countryOf(ip);
        }

        @Override
        public void close() throws IOException {
            if (delegate != null) {
                delegate.close();
            }
        }
    }
}
