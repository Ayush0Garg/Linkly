package com.ayush.shortener.analytics;

import java.util.Optional;

/** Maps an IP address to an ISO 3166 country code (e.g. "IN"). Empty when unknown or unavailable. */
public interface GeoLocator {

    Optional<String> countryOf(String ip);
}
