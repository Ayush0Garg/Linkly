package com.ayush.shortener.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param spaEnabled     serve the React app from this same process (one origin: first-party cookies, no CORS).
 *                       Off by default so local development and the API tests behave exactly as before.
 * @param staticLocation where the built React app lives: a classpath folder (inside the jar, the default) or a
 *                       {@code file:} folder. Must contain index.html and assets/.
 */
@ConfigurationProperties("app.web")
public record WebProperties(boolean spaEnabled, @DefaultValue("classpath:/static/") String staticLocation) {

    public String indexLocation() {
        return staticLocation.endsWith("/") ? staticLocation + "index.html" : staticLocation + "/index.html";
    }

    public String folder() {
        return staticLocation.endsWith("/") ? staticLocation : staticLocation + "/";
    }
}
