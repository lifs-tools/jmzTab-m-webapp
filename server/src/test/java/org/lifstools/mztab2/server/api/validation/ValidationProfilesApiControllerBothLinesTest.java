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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.validator.webapp.Application;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The profile list of an instance with engines for both lines (they are never contacted). */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
    "engines.mztabm[0].line=2.0", "engines.mztabm[0].url=http://localhost:9",
    "engines.mztabm[1].line=2.1", "engines.mztabm[1].url=http://localhost:9"})
@AutoConfigureMockMvc
class ValidationProfilesApiControllerBothLinesTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void listsProfilesOfBothLines() throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.id=='mztab-m-2.1-default')].title").value("mzTab-M 2.1 default rules"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.1.0-M", "2.1", "2.1.0-m", "2.1.3"})
    void filtersByVersionOrLine(String v) throws Exception {
        mvc.perform(get("/rest/v2/validation-profiles").queryParam("mzTabVersion", v)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("mztab-m-2.1-default"))
            .andExpect(jsonPath("$[0].version").value("1.0.0"));
    }
}
