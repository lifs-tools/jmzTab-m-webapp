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
package org.lifstools.mztab.validator.engine;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.MappingApplyRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * {@code POST /engine/v1/mappings/apply}. The engine applies only output mappings that target one of its own mzTab-M
 * versions, so the v3 example is answered with 200 by an engine that serves 2.0.0-M and with 400 by any other.
 */
class EngineMappingEndpointTest extends EngineContextTest {

    private static final String APPLY = "/engine/v1/mappings/apply";
    private static final String EXAMPLE = "/submission-vocabulary/examples/v3-example/";

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void theV3ExampleIsAppliedOnlyByAnEngineThatServesItsTarget() throws Exception {
        String body = objectMapper.writeValueAsString(v3Request("mztab-2.0.0-M"));
        if (servesTwoZero()) {
            mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(startsWith(
                    "MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tLCS-99999-01")));
        } else {
            mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.message").value(containsString("targets 2.0.0-M")));
        }
    }

    @Test
    void anUnknownMappingIsInvalid() throws Exception {
        String body = objectMapper.writeValueAsString(v3Request("nope"));
        mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void aBodyThatIsNotAMappingRequestIsInvalid() throws Exception {
        mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void aWrongMappingVersionIsInvalid() throws Exception {
        MappingApplyRequest r = v3Request("mztab-2.0.0-M");
        String body = objectMapper.writeValueAsString(new MappingApplyRequest(r.mapping(), "9.9.9", r.parameters(),
            r.instance(), r.text(), r.provenance(), r.inputMapping()));
        mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void anInputMappingIsInvalid() throws Exception {
        String body = objectMapper.writeValueAsString(v3Request("v3-workbook"));
        mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void aCellWithATabIsInvalid() throws Exception {
        Assumptions.assumeTrue(servesTwoZero(), "the mapping targets 2.0.0-M; another engine refuses it first");
        MappingApplyRequest r = v3Request("mztab-2.0.0-M");
        ((ObjectNode) r.instance().get("samples").get(0).get("species")).put("accession", "NCBITaxon:\t9606");
        mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(r)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"))
            .andExpect(jsonPath("$.message").value(containsString("tab or line break")));
    }

    @Test
    void anInstanceOfTheWrongShapeIsInvalid() throws Exception {
        Assumptions.assumeTrue(servesTwoZero(), "the mapping targets 2.0.0-M; another engine refuses it first");
        for (String field : new String[] {"samples", "lipids", "contacts"}) {
            MappingApplyRequest r = v3Request("mztab-2.0.0-M");
            ((ObjectNode) r.instance()).put(field, "not a list");
            mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(r)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        }
    }

    @Test
    void aMissingOrNullInstanceIsInvalid() throws Exception {
        for (String body : new String[] {
            "{\"mapping\":\"mztab-2.0.0-M\",\"mappingVersion\":\"1.3.0\"}",
            "{\"mapping\":\"mztab-2.0.0-M\",\"mappingVersion\":\"1.3.0\",\"instance\":null}"}) {
            mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        }
    }

    @Test
    void aNullParameterValueIsInvalid() throws Exception {
        String body = "{\"mapping\":\"mztab-2.0.0-M\",\"mappingVersion\":\"1.3.0\",\"instance\":{},"
            + "\"parameters\":{\"study_id\":null}}";
        mvc.perform(post(APPLY).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"))
            .andExpect(jsonPath("$.message").value(containsString("study_id")));
    }

    private boolean servesTwoZero() {
        return Arrays.stream(mzTabVersions).map(String::strip).anyMatch("2.0.0-M"::equals);
    }

    private MappingApplyRequest v3Request(String mapping) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        objectMapper.readTree(text("parameters.json")).get(0).properties()
            .forEach(e -> parameters.put(e.getKey(), e.getValue().asText()));
        JsonNode instance = objectMapper.readTree(text("instance.json"));
        return new MappingApplyRequest(mapping, "1.3.0", parameters, instance, "", true, "v3-workbook 1.2.0");
    }

    private static String text(String name) {
        try (InputStream in = EngineMappingEndpointTest.class.getResourceAsStream(EXAMPLE + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
