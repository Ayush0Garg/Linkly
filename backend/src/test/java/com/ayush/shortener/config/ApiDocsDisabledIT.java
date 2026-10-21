package com.ayush.shortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SWAGGER_ENABLED=false must remove the docs completely (e.g. for a locked-down production). */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ApiDocsDisabledIT {

    @Autowired MockMvc mvc;

    @Test
    void noDocsNoLandingRedirect() throws Exception {
        mvc.perform(get("/")).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void theRestOfTheAppIsUnaffected() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/links")).andExpect(status().isUnauthorized());
        mvc.perform(get("/linkly-nothere")).andExpect(status().isNotFound());
    }
}
