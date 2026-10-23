/*
 * Copyright 2026 LIFS Tools Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lifstools.mztab.validator.webapp.spa;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.validator.webapp.Application;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * Routing of the single-page app under a context path. The index.html served here is the test stand-in in
 * {@code src/test/resources/static}, so these tests do not depend on a frontend build.
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
class SpaControllerTest {

    static final String CTX = "/mztabvalidator";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    static MockHttpServletRequestBuilder ctxGet(String path) {
        return get(CTX + path).contextPath(CTX);
    }

    @Test
    void rootServesIndexWithContextBase() throws Exception {
        mvc.perform(ctxGet("/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(content().string(containsString("<base href=\"/mztabvalidator/\">")))
            .andExpect(content().string(not(containsString("<base href=\"/\""))));
    }

    @Test
    void rootWithoutContextPathKeepsSlashBase() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<base href=\"/\">")));
    }

    @Test
    void indexHtmlItselfIsRewritten() throws Exception {
        mvc.perform(ctxGet("/index.html"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(content().string(containsString("<base href=\"/mztabvalidator/\">")));
    }

    @Test
    void deepRouteServesIndex() throws Exception {
        mvc.perform(ctxGet("/result/" + UUID.randomUUID()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(content().string(containsString("<app-root>")))
            .andExpect(content().string(containsString("<base href=\"/mztabvalidator/\">")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/about", "/examples", "/no/such/route", "/validate/not-a-uuid"})
    void clientRoutesServeIndex(String path) throws Exception {
        mvc.perform(ctxGet(path))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<app-root>")));
    }

    @Test
    void oldValidateUrlRedirects() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(ctxGet("/validate/" + id + "?maxErrors=100&level=INFO&checkCvMapping=true"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", endsWith("/mztabvalidator/result/" + id)));
    }

    @Test
    void oldDeleteUrlNeverDeletes() throws Exception {
        String id = createValidation();
        mvc.perform(ctxGet("/validate/" + id + "/delete"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", endsWith("/mztabvalidator/result/" + id + "?confirmDelete=true")));
        mvc.perform(ctxGet("/rest/v2/validations/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void restUnknownPathIsJson404() throws Exception {
        mvc.perform(ctxGet("/rest/v2/nope").accept(MediaType.TEXT_HTML))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.path").value("/mztabvalidator/rest/v2/nope"));
    }

    @Test
    void restWrongMethodIsJson405() throws Exception {
        mvc.perform(delete(CTX + "/rest/v2/validatePlain").contextPath(CTX))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().exists("Allow"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    @Test
    void assetsAreNotForwarded() throws Exception {
        mvc.perform(ctxGet("/missing.js"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(not(containsString("<app-root>"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/nope", "/swagger-ui/nope", "/v3/nope", "/brand/missing.png"})
    void excludedPathsAreNotForwarded(String path) throws Exception {
        mvc.perform(ctxGet(path))
            .andExpect(status().isNotFound())
            .andExpect(content().string(not(containsString("<app-root>"))));
    }

    @Test
    void actuatorHealthStillWorks() throws Exception {
        mvc.perform(ctxGet("/actuator/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void swaggerUiIsStillServed() throws Exception {
        mvc.perform(ctxGet("/swagger-ui.html")).andExpect(status().is3xxRedirection());
        mvc.perform(ctxGet("/swagger-ui/index.html"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("swagger-ui")));
        mvc.perform(ctxGet("/v3/api-docs/mztab-m-api")).andExpect(status().isOk());
    }

    @Test
    void forwardedPrefixIsUsedForTheBase() throws Exception {
        mvc.perform(get("/").header("X-Forwarded-Prefix", "/mztab-validator"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<base href=\"/mztab-validator/\">")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/x\"><script>alert(1)</script>", "/a b"})
    void unsafeForwardedPrefixFallsBackToTheServerContextPath(String prefix) throws Exception {
        mvc.perform(get("/").header("X-Forwarded-Prefix", prefix))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<base href=\"/\">")))
            .andExpect(content().string(not(containsString("<script>"))));
    }

    /** Spring Security's firewall rejects these already; they must never end up in the base tag. */
    @ParameterizedTest
    @ValueSource(strings = {"//evil.example", "/a//b", "/a%2F..", "/évil"})
    void prefixesTheFirewallRejectsNeverReachThePage(String prefix) throws Exception {
        mvc.perform(get("/").header("X-Forwarded-Prefix", prefix))
            .andExpect(content().string(not(containsString("<base href=\"" + prefix))));
    }

    private String createValidation() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            bytes = in.readAllBytes();
        }
        String body = mvc.perform(multipart(CTX + "/rest/v2/validations")
                .file(new MockMultipartFile("file", "lipidomics-example.mzTab", "text/plain", bytes))
                .contextPath(CTX))
            .andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }
}
