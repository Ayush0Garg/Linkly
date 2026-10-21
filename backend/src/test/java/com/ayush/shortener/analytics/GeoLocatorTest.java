package com.ayush.shortener.analytics;

import java.io.File;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against the real DB-IP database when it has been downloaded (see README); skipped otherwise. */
class GeoLocatorTest {

    private static final File DB = new File(".dev-data/geoip/dbip.mmdb");
    private static MaxMindGeoLocator geo;

    @BeforeAll
    static void open() throws Exception {
        assumeTrue(DB.isFile(), "GeoIP database not downloaded; skipping");
        geo = new MaxMindGeoLocator(DB);
    }

    @AfterAll
    static void close() throws Exception {
        if (geo != null) {
            geo.close();
        }
    }

    @Test
    void resolvesPublicAddresses() {
        assertThat(geo.countryOf("8.8.8.8")).hasValue("US");
        assertThat(geo.countryOf("81.2.139.142")).hasValue("GB");
        // Anycast addresses are placed inconsistently by every provider, so only check that IPv6 resolves.
        assertThat(geo.countryOf("2001:4860:4860::8888")).hasValueSatisfying(c -> assertThat(c).matches("[A-Z]{2}"));
    }

    @Test
    void privateLoopbackAndGarbageAddressesGiveNothing() {
        assertThat(geo.countryOf("127.0.0.1")).isEmpty();
        assertThat(geo.countryOf("10.1.2.3")).isEmpty();
        assertThat(geo.countryOf("not an ip")).isEmpty();
        assertThat(geo.countryOf("")).isEmpty();
        assertThat(geo.countryOf(null)).isEmpty();
    }
}
