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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.lifstools.mztab.validator.webapp.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
    "engines.mztabm[0].line=2.0", "engines.mztabm[0].url=http://localhost:9"})
@AutoConfigureMockMvc
class ValidationProfilesApiControllerTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }


    @Autowired
    MockMvc mvc;

    @Test
    void profilesEndpointListsCatalog() throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("mztab-m-2.0-default"))
            .andExpect(jsonPath("$[0].version").value("1.1.0"));
    }

    @Test
    void listsOnlyProfilesOfConfiguredLines() throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='mztab-m-2.0-default')].version").value("1.1.0"))
            .andExpect(jsonPath("$[?(@.id=='mztab-m-2.1-default')]").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.x", "../etc", "", "2.1.0-M-extra", "2.1.0-"})
    void rejectsInvalidVersionFilter(String v) throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles").queryParam("mzTabVersion", v))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
    }

    @Test
    void filterForALineWithoutEngineIsEmpty() throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles").queryParam("mzTabVersion", "2.1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void downloadsMappingXml() throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles/{id}/{version}/mapping", "mztab-m-2.1-default", "1.0.0"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("application/xml"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"mztab-m-2.1-default-1.0.0.xml\""))
            .andExpect(result -> assertTrue(result.getResponse().getContentAsString().contains("CvMapping")));
    }

    @Test
    void encodedSlashInIdIs400() throws Exception {
        // an encoded slash is refused by the servlet/security layer before the controller, so no app error body
        mvc.perform(get("/rest/v2/validation-profiles/..%2Fx/1.0.0/mapping"))
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({"mztab-m-2.0-default,1.0", "Mztab,1.0.0", "mztab-m-2.0-default,1.0.0.xml",
        "-x,1.0.0", "mztab-m-2.0-default,01.0.00000"})
    void rejectsInvalidIdOrVersion(String id, String version) throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles/{id}/{version}/mapping", id, version))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value("BAD_REQUEST"));
    }

    @Test
    void unknownProfileIs404() throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles/{id}/{version}/mapping", "nope", "1.0.0"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value("NOT_FOUND"));
        mvc.perform(get("/rest/v2/validation-profiles/{id}/{version}/mapping", "mztab-m-2.0-default", "9.9.9"))
            .andExpect(status().isNotFound());
    }
}
