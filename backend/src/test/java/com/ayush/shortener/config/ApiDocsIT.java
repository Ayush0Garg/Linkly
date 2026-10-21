package com.ayush.shortener.config;

import java.util.ArrayList;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("embedded")
class ApiDocsIT {

    @Autowired MockMvc mvc;

    String apiDocs() throws Exception {
        return mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theRootRedirectsStraightToSwaggerUiWithoutSigningIn() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(header().string("Location", "/swagger-ui/index.html"));
        mvc.perform(head("/")).andExpect(status().isFound());
    }

    @Test
    void swaggerUiIsPublicAndGetsItsOwnContentSecurityPolicy() throws Exception {
        MvcResult page = mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk()).andReturn();
        String csp = page.getResponse().getHeader("Content-Security-Policy");

        // Loose enough for the docs page to load its own scripts and styles, but still same-origin only.
        assertThat(csp).contains("default-src 'self'").doesNotContain("default-src 'none'").doesNotContain("*");
        assertThat(csp).contains("frame-ancestors 'none'");
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    void theApiItselfKeepsTheStrictPolicy() throws Exception {
        for (String path : new String[] {"/api/links", "/v3/api-docs", "/actuator/health"}) {
            mvc.perform(get(path)).andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
        }
    }

    @Test
    void protectedEndpointsStayProtectedEvenThoughTheDocsArePublic() throws Exception {
        mvc.perform(get("/api/links")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stats/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/../api/links")).andExpect(status().is4xxClientError());
    }

    @Test
    void theDocumentDescribesEveryEndpoint() throws Exception {
        String json = apiDocs();
        List<String> paths = new ArrayList<>(((java.util.Map<String, Object>) JsonPath.read(json, "$.paths")).keySet());
        assertThat(paths).contains("/api/auth/register", "/api/auth/login", "/api/auth/google", "/api/auth/refresh",
                "/api/auth/logout", "/api/auth/me", "/api/auth/csrf", "/api/links", "/api/links/{id}",
                "/api/links/{id}/stats", "/api/stats/overview", "/{code}");
        assertThat(paths).noneMatch(p -> p.startsWith("/actuator")).noneMatch(p -> p.equals("/"));

        assertThat((JSONArray) JsonPath.read(json, "$.tags[*].name")).contains("Auth", "Links", "Analytics", "Redirect");
        assertThat((String) JsonPath.read(json, "$.info.description")).contains("/api/auth/csrf").contains("/api/auth/login");
    }

    @Test
    void everyOperationHasASummaryAndNoInternalParametersLeakIn() throws Exception {
        String json = apiDocs();
        JSONArray summaries = JsonPath.read(json, "$.paths.*.*.summary");
        JSONArray operationIds = JsonPath.read(json, "$.paths.*.*.operationId");
        assertThat(summaries).hasSameSizeAs(operationIds).allSatisfy(s -> assertThat((String) s).isNotBlank());

        // Things Swagger cannot or should not send: cookies, the CSRF token object, the signed-in principal.
        JSONArray parameterNames = JsonPath.read(json, "$.paths.*.*.parameters[*].name");
        assertThat(parameterNames).doesNotContain("token", "refresh_token", "access_token", "jwt");
        JSONArray parameterLocations = JsonPath.read(json, "$.paths.*.*.parameters[*].in");
        assertThat(parameterLocations).doesNotContain("cookie");
    }

    @Test
    void errorResponsesAreDocumentedForTheAuthAndLinkOperations() throws Exception {
        String json = apiDocs();
        assertThat((java.util.Map<String, Object>) JsonPath.read(json, "$.paths['/api/auth/register'].post.responses"))
                .containsKeys("201", "400", "409");
        assertThat((java.util.Map<String, Object>) JsonPath.read(json, "$.paths['/{code}'].get.responses"))
                .containsKeys("302", "404", "410");
        assertThat((java.util.Map<String, Object>) JsonPath.read(json, "$.paths['/api/links/{id}'].delete.responses"))
                .containsKeys("204", "404");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyErrorResponseIsDocumentedAsProblemJsonAndNeverAsTheSuccessBody() throws Exception {
        String json = apiDocs();
        java.util.Map<String, java.util.Map<String, Object>> paths = JsonPath.read(json, "$.paths");
        int errorResponses = 0;
        for (var path : paths.entrySet()) {
            for (var operation : ((java.util.Map<String, java.util.Map<String, Object>>) (Object) path.getValue()).entrySet()) {
                var responses = (java.util.Map<String, java.util.Map<String, Object>>) operation.getValue().get("responses");
                for (var response : responses.entrySet()) {
                    var content = (java.util.Map<String, Object>) response.getValue().get("content");
                    if (response.getKey().startsWith("4") || response.getKey().startsWith("5")) {
                        errorResponses++;
                        String where = operation.getKey() + " " + path.getKey() + " " + response.getKey();
                        assertThat(content).as(where).containsOnlyKeys("application/problem+json");
                        assertThat(((java.util.Map<String, Object>) content.get("application/problem+json").getClass().cast(content.get("application/problem+json"))).get("schema").toString())
                                .as(where).contains("#/components/schemas/Problem");
                    } else if (content != null) {
                        assertThat(content).as("success response %s %s %s", operation.getKey(), path.getKey(), response.getKey())
                                .doesNotContainKey("application/problem+json");
                    }
                }
            }
        }
        assertThat(errorResponses).as("there are error responses documented").isGreaterThan(20);
        assertThat((java.util.Map<String, Object>) JsonPath.read(json, "$.components.schemas.Problem.properties"))
                .containsKeys("title", "status", "detail", "instance", "errors", "retryAfterSeconds");
    }

    @Test
    void theRateLimitedOperationsDocumentA429() throws Exception {
        String json = apiDocs();
        for (String op : new String[] {"$.paths['/api/auth/login'].post", "$.paths['/api/auth/register'].post",
                "$.paths['/api/auth/google'].post", "$.paths['/api/links'].post", "$.paths['/api/links/{id}/qr'].get", "$.paths['/{code}'].get"}) {
            assertThat((java.util.Map<String, Object>) JsonPath.read(json, op + ".responses")).as(op).containsKey("429");
        }
    }

    @Test
    void requestBodiesCarryTheirValidationRules() throws Exception {
        String json = apiDocs();
        assertThat((Integer) JsonPath.read(json, "$.components.schemas.CreateLinkRequest.properties.longUrl.maxLength"))
                .isEqualTo(8192);
        assertThat((Integer) JsonPath.read(json, "$.components.schemas.RegisterRequest.properties.password.minLength"))
                .isEqualTo(10);
    }

    @Test
    void swaggerUiIsConfiguredToSendTheCsrfHeaderSoTryItOutWorks() throws Exception {
        // springdoc injects a request interceptor into the page's initializer script: it copies the XSRF-TOKEN
        // cookie into the X-XSRF-TOKEN header, which is what our CSRF protection expects on POST / DELETE.
        String script = mvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(script).contains("requestInterceptor").contains("XSRF-TOKEN").contains("X-XSRF-TOKEN");
    }
}
