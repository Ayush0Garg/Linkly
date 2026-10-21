package com.ayush.shortener.link;

import java.time.Instant;

import com.ayush.shortener.user.User;
import com.ayush.shortener.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class RedirectControllerIT {

    @Autowired MockMvc mvc;
    @Autowired LinkService service;
    @Autowired UserRepository users;

    long userId;

    @BeforeEach
    void createUser() {
        userId = users.save(new User("sub-" + System.nanoTime(), "r" + System.nanoTime() + "@example.com", "R", null, Instant.now())).getId();
    }

    @Test
    void redirectsWith302AndNoStoreWithoutAuthentication() throws Exception {
        Link link = service.create(userId, new CreateLinkCommand("https://example.com/target?x=1", null, null));

        mvc.perform(get("/" + link.getCode()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target?x=1"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void unknownCodeIs404ProblemJson() throws Exception {
        mvc.perform(get("/nosuchcode"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    @Test
    void deactivatedLinkIs410() throws Exception {
        Link link = service.create(userId, new CreateLinkCommand("https://example.com", null, null));
        service.deactivate(userId, link.getId());

        mvc.perform(get("/" + link.getCode())).andExpect(status().isGone());
    }

    @Test
    void healthIsPublicAndOtherPathsRequireAuth() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/links")).andExpect(status().isUnauthorized());
    }
}
