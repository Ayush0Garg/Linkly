package com.ayush.shortener.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default (app.web.spa-enabled unset): exactly the behaviour from before the web app could be served. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class SpaDisabledIT {

    @Autowired MockMvc mvc;

    @Test
    void theRootStillRedirectsToSwaggerAndCarriesTheStrictPolicy() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(header().string("Location", "/swagger-ui/index.html"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
    }

    @Test
    void noAppPagesAreServed() throws Exception {
        // /login is not a page here and not a short code: it needs sign-in like any other unknown path
        mvc.perform(get("/login")).andExpect(status().isNotFound());
        mvc.perform(get("/links/42")).andExpect(status().isUnauthorized());
        mvc.perform(get("/index.html")).andExpect(status().isUnauthorized());
        assertThat(mvc.perform(get("/assets/app.js")).andReturn().getResponse().getContentAsString()).doesNotContain("<html");
    }
}
