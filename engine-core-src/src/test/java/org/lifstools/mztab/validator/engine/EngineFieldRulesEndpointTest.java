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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/** {@code POST /engine/v1/field-rules}. */
class EngineFieldRulesEndpointTest extends EngineContextTest {

    private static final String FIELD_RULES = "/engine/v1/field-rules";

    @Test
    void aMappingAndATextGivePresence() throws Exception {
        MockMultipartFile mapping = new MockMultipartFile("mapping", "mapping.xml", MediaType.APPLICATION_XML_VALUE,
            Fixtures.resource(Fixtures.BUNDLED_MAPPING));
        MockMultipartFile file = new MockMultipartFile("file", "a.mzTab", "text/plain",
            ofThisLine(Fixtures.LIPIDOMICS));
        mvc.perform(multipart(FIELD_RULES).file(mapping).file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parsed").value(true))
            .andExpect(jsonPath("$.rules[0].id").isNotEmpty())
            .andExpect(jsonPath("$.rules[0].terms").isArray())
            .andExpect(jsonPath("$.rules[0].present").isBoolean());
    }

    @Test
    void aMappingAloneGivesTheRulesWithoutPresence() throws Exception {
        MockMultipartFile mapping = new MockMultipartFile("mapping", "mapping.xml", MediaType.APPLICATION_XML_VALUE,
            Fixtures.resource(Fixtures.BUNDLED_MAPPING));
        mvc.perform(multipart(FIELD_RULES).file(mapping))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.parsed").value(false))
            .andExpect(jsonPath("$.rules[0].id").isNotEmpty())
            .andExpect(jsonPath("$.rules[0].present").doesNotExist());
    }

    @Test
    void aMappingOverTheCapIsTooLarge() throws Exception {
        MockMultipartFile mapping = new MockMultipartFile("mapping", "mapping.xml", MediaType.APPLICATION_XML_VALUE,
            new byte[(int) EngineController.MAX_MAPPING_BYTES + 1]);
        mvc.perform(multipart(FIELD_RULES).file(mapping))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("file_too_large"));
    }

    @Test
    void aMappingThatIsNotCvMappingXmlIsInvalid() throws Exception {
        MockMultipartFile mapping = new MockMultipartFile("mapping", "mapping.xml", MediaType.APPLICATION_XML_VALUE,
            "<notAMapping/>".getBytes());
        mvc.perform(multipart(FIELD_RULES).file(mapping))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void aRequestWithoutAMappingIsInvalid() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.mzTab", "text/plain", "x".getBytes());
        mvc.perform(multipart(FIELD_RULES).file(file))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"));
    }
}
