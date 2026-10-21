package com.ayush.shortener.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The frontend loads Google's sign-in script and button only when a Google client id is configured; the policy follows. */
@SpringBootTest(properties = {"app.web.spa-enabled=true", "app.web.static-location=classpath:/spa-test/",
        "app.auth.google-client-id=test-client.apps.googleusercontent.com"})
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class SpaGoogleIT {

    @Autowired MockMvc mvc;

    @Test
    void googleSignInIsAllowedExactlyWhereItIsNeededAndNothingElse() throws Exception {
        String csp = mvc.perform(get("/login")).andReturn().getResponse().getHeader("Content-Security-Policy");

        assertThat(csp).contains("script-src 'self' https://accounts.google.com/gsi/client;")
                .contains("frame-src https://accounts.google.com/gsi/;")
                .contains("connect-src 'self' https://accounts.google.com/gsi/;")
                .contains("style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style;")
                .doesNotContain("*", " https: ", "https://accounts.google.com;");
        // the API stays strict regardless
        assertThat(mvc.perform(get("/api/links")).andReturn().getResponse().getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; frame-ancestors 'none'");
    }
}
