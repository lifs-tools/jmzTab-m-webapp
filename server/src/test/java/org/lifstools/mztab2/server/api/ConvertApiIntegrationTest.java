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
package org.lifstools.mztab2.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * The conversion REST API: tab separated mzTab-M to its JSON representation
 * ({@code /convertPlain}) and back ({@code /convert}).
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ActiveProfiles("microservice")
@AutoConfigureMockMvc
class ConvertApiIntegrationTest {

    static final MediaType TSV = MediaType.parseMediaType("text/tab-separated-values");

    /** The metadata of a file without any of the required metadata but the version and id. */
    static final String METADATA_ONLY = """
        {"metadata": {"prefix": "MTD", "mzTab-version": "2.0.0-M", "mzTab-ID": "metadata-only"}}""";

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
    ObjectMapper objectMapper;

    @Test
    void convertPlainReturnsTheModelAsJson() throws Exception {
        mvc.perform(post("/rest/v2/convertPlain")
                .contentType(TSV).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.metadata['mzTab-version']").value("2.0.0-M"))
            .andExpect(jsonPath("$.metadata['mzTab-ID']").value("PRIDE_1234"))
            .andExpect(jsonPath("$.smallMoleculeSummary").isArray());
    }

    @Test
    void convertPlainAcceptsPlainText() throws Exception {
        mvc.perform(post("/rest/v2/convertPlain")
                .contentType(MediaType.TEXT_PLAIN)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void convertPlainReportsAFileWithErrorsAs422() throws Exception {
        mvc.perform(post("/rest/v2/convertPlain")
                .contentType(TSV).accept(MediaType.APPLICATION_JSON)
                .content("this is not an mzTab file"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$", not(hasSize(0))))
            .andExpect(jsonPath("$[*].message_type", everyItem(is("error"))));
    }

    @Test
    void convertWritesTabSeparatedMzTab() throws Exception {
        String json = toJson(resource("/metabolomics/minimal-m-2.0.mztab"));
        mvc.perform(post("/rest/v2/convert")
                .contentType(MediaType.APPLICATION_JSON).accept(TSV)
                .content(json))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(TSV))
            .andExpect(content().string(startsWith("MTD\tmzTab-version\t2.0.0-M")));
    }

    @Test
    void convertReportsAModelThatDoesNotValidateAs422() throws Exception {
        mvc.perform(post("/rest/v2/convert")
                .contentType(MediaType.APPLICATION_JSON).accept(TSV)
                .content(METADATA_ONLY))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$", not(hasSize(0))))
            .andExpect(jsonPath("$[*].message_type", everyItem(is("error"))));
    }

    @Test
    void convertWithoutValidationWritesAModelThatDoesNotValidate() throws Exception {
        mvc.perform(post("/rest/v2/convert").param("validate", "false")
                .contentType(MediaType.APPLICATION_JSON).accept(TSV)
                .content(METADATA_ONLY))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(TSV))
            .andExpect(content().string(startsWith("MTD\tmzTab-version\t2.0.0-M")));
    }

    @Test
    void convertReportsAModelThatCannotBeWrittenAs422() throws Exception {
        mvc.perform(post("/rest/v2/convert").param("validate", "false")
                .contentType(MediaType.APPLICATION_JSON).accept(TSV)
                .content("{}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].message_type").value("error"));
    }

    @Test
    void convertReportsUnreadableJsonAs400() throws Exception {
        mvc.perform(post("/rest/v2/convert")
                .contentType(MediaType.APPLICATION_JSON).accept(TSV)
                .content("{\"metadata\": "))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    /** Both directions read and write UTF-8, whether or not the client names a charset. */
    @Test
    void conversionKeepsNonAsciiCharacters() throws Exception {
        String json = toJson(resource("/metabolomics/gcxgc-ms-example.mztab"));
        assertThat(json).contains("n-alkanes C10–C36");
        byte[] tsv = mvc.perform(post("/rest/v2/convert")
                .contentType(MediaType.APPLICATION_JSON).accept(TSV)
                .content(json.getBytes(StandardCharsets.UTF_8)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(tsv, StandardCharsets.UTF_8)).contains("n-alkanes C10–C36");
    }

    /**
     * Tab separated to JSON and back gives the same model, so that an edited
     * file can be stored without losing anything but its comment lines:
     * jmzTab-m's writer does not write them.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/metabolomics/minimal-m-2.0.mztab",
        "/metabolomics/lipidomics-example.mzTab",
        "/metabolomics/gcxgc-ms-example.mztab",
        "/metabolomics/MTBLS263.mztab",
        "/metabolomics/example_study_variable_group.mztab",
        "/metabolomics/StandardMix_positive_exportSpeciesLevel.mzTab",
        "/metabolomics/xcms-msio-0.0.11-MTBLS4381-onlySMF-trimmed.mztab"})
    void roundTripKeepsTheModel(String file) throws Exception {
        String json = toJson(resource(file));
        String tsv = mvc.perform(post("/rest/v2/convert")
                .contentType(MediaType.APPLICATION_JSON).accept(TSV)
                .content(json))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String again = toJson(tsv.getBytes(StandardCharsets.UTF_8));
        ObjectNode first = (ObjectNode) objectMapper.readTree(json);
        ObjectNode second = (ObjectNode) objectMapper.readTree(again);
        assertThat(second.path("comment")).isEmpty();
        first.remove("comment");
        second.remove("comment");
        List<String> differences = new ArrayList<>();
        diff("", first, second, differences);
        assertThat(differences).isEmpty();
    }

    /** The JSON pointers at which two trees differ, with both values. */
    private static void diff(String pointer, JsonNode first, JsonNode second, List<String> differences) {
        if (first.isContainerNode() && first.getNodeType() == second.getNodeType()) {
            Set<String> names = new TreeSet<>();
            first.fieldNames().forEachRemaining(names::add);
            second.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                diff(pointer + "/" + name, first.path(name), second.path(name), differences);
            }
            if (first.size() != second.size()) {
                differences.add(pointer + ": " + first.size() + " -> " + second.size() + " entries");
            }
            for (int i = 0; first.isArray() && i < Math.min(first.size(), second.size()); i++) {
                diff(pointer + "/" + i, first.get(i), second.get(i), differences);
            }
        } else if (!first.equals(second)) {
            differences.add(pointer + ": " + first + " -> " + second);
        }
    }

    @Test
    void openApiDocumentDescribesTheConversionMediaTypes() throws Exception {
        mvc.perform(get("/v3/api-docs/mztab-m-api"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/rest/v2/convert'].post.requestBody.content['application/json']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/convert'].post.responses['200'].content['text/tab-separated-values']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/convert'].post.responses['422'].content['application/json']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/convertPlain'].post.requestBody.content['text/tab-separated-values']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/convertPlain'].post.responses['200'].content['application/json']").exists())
            .andExpect(jsonPath("$.paths['/rest/v2/convertPlain'].post.responses['422'].content['application/json']").exists());
    }

    private String toJson(byte[] tsv) throws Exception {
        return mvc.perform(post("/rest/v2/convertPlain")
                .contentType(TSV).accept(MediaType.APPLICATION_JSON)
                .content(tsv))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }
}
