package com.ayush.shortener.config;

import java.io.IOException;
import java.nio.file.Path;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

/**
 * Starts a real Postgres inside the JVM for local dev and tests (profile "embedded").
 * With a data-dir the data survives restarts; without one it uses a throwaway temp dir and a random port.
 */
@Configuration
@Profile("embedded")
class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres(
            @Value("${app.embedded-postgres.data-dir:}") String dataDir,
            @Value("${app.embedded-postgres.port:0}") int port) throws IOException {
        EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder();
        if (StringUtils.hasText(dataDir)) {
            builder.setDataDirectory(Path.of(dataDir).toAbsolutePath()).setCleanDataDirectory(false);
        }
        if (port > 0) {
            builder.setPort(port);
        }
        return builder.start();
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(EmbeddedPostgres postgres) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl("postgres", "postgres") + "&reWriteBatchedInserts=true");
        config.setUsername("postgres");
        config.setMaximumPoolSize(10);
        // zonky stops this Postgres from its own JVM shutdown hook, concurrently with Spring closing the context.
        // Any last database call made while shutting down (e.g. the final click flush) would otherwise wait out
        // Hikari's default 30 s connection timeout. Fail fast instead; a real external database has no such race.
        config.setConnectionTimeout(2_000);
        return new HikariDataSource(config);
    }
}
