package com.ayush.shortener.analytics;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;

/**
 * Offline lookup against a MaxMind-format .mmdb file (GeoLite2 or DB-IP Lite): no network call per click, and the
 * visitor's IP never leaves the process.
 */
class MaxMindGeoLocator implements GeoLocator, AutoCloseable {

    private final DatabaseReader reader;

    MaxMindGeoLocator(File database) throws IOException {
        this.reader = new DatabaseReader.Builder(database).build();
    }

    @Override
    public Optional<String> countryOf(String ip) {
        if (ip == null || ip.isBlank()) {
            return Optional.empty();
        }
        try {
            // Always an IP literal from the servlet container, so this never triggers a DNS lookup.
            InetAddress address = InetAddress.getByName(ip);
            return reader.tryCountry(address)
                    .map(response -> response.country().isoCode())
                    .filter(code -> code != null && !code.isBlank());
        } catch (IOException | GeoIp2Exception | RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
