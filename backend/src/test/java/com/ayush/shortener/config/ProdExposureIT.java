package com.ayush.shortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the "prod" profile (the Docker image) exposes on the public internet. */
@SpringBootTest(properties = "CODE_ALPHABET=mUk5Z1EqatyliD92vOwWsFCRV7QMrbNH6YohKXSJTp3ABfcnG4LxgI0edPzj8u")
@AutoConfigureMockMvc
@ActiveProfiles({"embedded", "prod"})
class ProdExposureIT {

    @Autowired MockMvc mvc;

    @Test
    void metricsAreNotPublishedButTheHealthProbesAre() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void theFullHealthCheckThatAsksTheDatabaseIsNotPublic() throws Exception {
        // anyone could otherwise call it all day and keep a scale-to-zero database awake
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/db")).andExpect(status().isUnauthorized());
    }
}
