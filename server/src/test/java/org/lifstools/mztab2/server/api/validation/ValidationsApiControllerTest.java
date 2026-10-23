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
package org.lifstools.mztab2.server.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.session.StorageFullException;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
class ValidationsApiControllerTest {

    static final String UNKNOWN = "Unknown or expired validation.";

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

    static byte[] example() throws Exception {
        try (InputStream in = ValidationsApiControllerTest.class.getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            return in.readAllBytes();
        }
    }

    static MockMultipartFile file(String name) throws Exception {
        return new MockMultipartFile("file", name, "text/plain", example());
    }

    JsonNode create(String name) throws Exception {
        MvcResult r = mvc.perform(multipart("/rest/v2/validations").file(file(name)).param("level", "INFO"))
            .andExpect(status().isAccepted()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode awaitFinished(String id) throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            JsonNode n = json.readTree(mvc.perform(get("/rest/v2/validations/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
            if ("FINISHED".equals(n.get("status").asText()) || "FAILED".equals(n.get("status").asText())) {
                return n;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("validation did not finish");
    }

    @Test
    void createReturns202AndLocation() throws Exception {
        MvcResult r = mvc.perform(multipart("/rest/v2/validations").file(file("a.mzTab")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.fileName").value("a.mzTab")).andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("id").asText();
        assertTrue(r.getResponse().getHeader("Location").endsWith("/rest/v2/validations/" + id));
        awaitFinished(id);
    }

    @Test
    void pollingReachesFinished() throws Exception {
        JsonNode created = create("poll.mzTab");
        assertEquals("FINISHED", awaitFinished(created.get("id").asText()).get("status").asText());
    }

    @Test
    void profileAndMappingTogetherIs400() throws Exception {
        mvc.perform(multipart("/rest/v2/validations").file(file("a.mzTab"))
            .file(new MockMultipartFile("mappingFile", "m.xml", "text/xml", "<x/>".getBytes()))
            .param("validationProfile", "mztab-m-2.0-default"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void maxErrorsOutOfRangeIs400() throws Exception {
        for (String v : new String[]{"0", "501"}) {
            mvc.perform(multipart("/rest/v2/validations").file(file("a.mzTab")).param("maxErrors", v))
                .andExpect(status().isBadRequest());
        }
    }

    @Test
    void hostileFileNameIsStoredSafely() throws Exception {
        for (String name : new String[]{"../../evil.mzTab", "..\\..\\evil.mzTab"}) {
            MvcResult r = mvc.perform(multipart("/rest/v2/validations").file(file(name)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileName").value("evil.mzTab")).andReturn();
            awaitFinished(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
        }
        // a traversal would land one or two levels above the storage root
        assertTrue(!Files.exists(storageRoot.resolve("../evil.mzTab")));
        assertTrue(!Files.exists(storageRoot.resolve("../../evil.mzTab")));
        assertTrue(!Files.exists(storageRoot.resolve("../../../evil.mzTab")));
    }

    @Test
    void nonUuidIdIs404() throws Exception {
        mvc.perform(get("/rest/v2/validations/not-a-uuid"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value(UNKNOWN));
    }

    @Test
    void deleteThenGetIs404() throws Exception {
        String id = create("del.mzTab").get("id").asText();
        awaitFinished(id);
        mvc.perform(delete("/rest/v2/validations/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/rest/v2/validations/" + id)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value(UNKNOWN));
        mvc.perform(delete("/rest/v2/validations/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void reportCsvHasHeaderAndRows() throws Exception {
        String id = create("rep.mzTab").get("id").asText();
        awaitFinished(id);
        MvcResult r = mvc.perform(get("/rest/v2/validations/" + id + "/report").param("format", "csv"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"rep.mzTab-validation.csv\""))
            .andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals("level,line,category,code,message", body.lines().findFirst().orElseThrow());
        mvc.perform(get("/rest/v2/validations/" + id + "/report").param("format", "json"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"rep.mzTab-validation.json\""))
            .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/rest/v2/validations/" + id + "/report").param("format", "xml"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void metadataEndpointsWork() throws Exception {
        String id = create("meta.mzTab").get("id").asText();
        awaitFinished(id);
        MvcResult r = mvc.perform(get("/rest/v2/validations/" + id + "/metadata")).andExpect(status().isOk())
            .andReturn();
        JsonNode groups = json.readTree(r.getResponse().getContentAsString());
        assertTrue(groups.size() > 0);
        String group = groups.get(0).get("name").asText();
        mvc.perform(get("/rest/v2/validations/" + id + "/metadata/" + group).param("size", "5"))
            .andExpect(status().isOk());
        mvc.perform(get("/rest/v2/validations/" + UUID.randomUUID() + "/metadata")).andExpect(status().isNotFound());
        mvc.perform(get("/rest/v2/validations/" + id + "/metadata/no-such-group"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void tablesEndpointPagesWithColumns() throws Exception {
        String id = create("tab.mzTab").get("id").asText();
        awaitFinished(id);
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("columns", "SML_ID").param("size", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.columns[0]").value("SML_ID"))
            .andExpect(jsonPath("$.columns.length()").value(1))
            .andExpect(jsonPath("$.rows[0].cells.length()").value(1));
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("columns", "NOPE"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("size", "201"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/xyz")).andExpect(status().isNotFound());
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml").param("filter", "SML_ID:zzzzqqq"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalRows").value(0));
    }

    @Test
    void absentTableIs404() throws Exception {
        mvc.perform(get("/rest/v2/validations/" + UUID.randomUUID() + "/tables/sml"))
            .andExpect(status().isNotFound());
        // a file that is not mzTab never gets section tables
        MvcResult r = mvc.perform(multipart("/rest/v2/validations")
            .file(new MockMultipartFile("file", "junk.txt", "text/plain", "not an mzTab file".getBytes())))
            .andExpect(status().isAccepted()).andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("id").asText();
        awaitFinished(id);
        mvc.perform(get("/rest/v2/validations/" + id + "/tables/sml")).andExpect(status().isNotFound());
    }

    @Test
    void openApiListsNewEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs/mztab-m-api")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/rest/v2/validations']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/validations/{id}/tables/{section}']").exists());
    }
}

@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
    "validation.custom-mappings.enabled=false"})
@AutoConfigureMockMvc
class ValidationsApiCustomMappingsDisabledTest {

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

    @Test
    void customMappingDisabledIs403() throws Exception {
        mvc.perform(multipart("/rest/v2/validations").file(ValidationsApiControllerTest.file("a.mzTab"))
            .file(new MockMultipartFile("mappingFile", "m.xml", "text/xml", "<x/>".getBytes())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").exists());
    }
}

/** MockMvc bypasses the servlet container's multipart limit, so this one runs against a real server. */
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@TestPropertySource(properties = "spring.servlet.multipart.max-file-size=1KB")
class ValidationsApiUploadLimitTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @LocalServerPort
    int port;

    @Value("${server.servlet.contextPath:}")
    String contextPath;

    @Test
    void uploadTooLargeIsJson413() throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(ValidationsApiControllerTest.example()) {
            @Override
            public String getFilename() {
                return "big.mzTab";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = new TestRestTemplate().exchange(
            "http://localhost:" + port + ("/".equals(contextPath) ? "" : contextPath) + "/rest/v2/validations", HttpMethod.POST, new HttpEntity<>(body, headers),
            String.class);
        assertEquals(413, response.getStatusCode().value(), String.valueOf(response.getBody()));
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON));
        assertTrue(response.getBody().contains("\"status\""));
    }
}

@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
class ValidationsApiStorageFullTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @MockitoBean
    ValidationSessionService sessions;

    @Autowired
    MockMvc mvc;

    @Test
    void storageFullIsJson503() throws Exception {
        when(sessions.submit(any(), any(), any(), any())).thenThrow(new StorageFullException());
        mvc.perform(multipart("/rest/v2/validations").file(ValidationsApiControllerTest.file("a.mzTab")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("The validator's storage is full. Please try again later."));
    }
}
