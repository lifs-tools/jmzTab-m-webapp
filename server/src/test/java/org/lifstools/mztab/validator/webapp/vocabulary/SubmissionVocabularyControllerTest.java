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
package org.lifstools.mztab.validator.webapp.vocabulary;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The published files of the submission vocabulary: what is served, with which type, and what is refused. */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class SubmissionVocabularyControllerTest {

    private static final String BASE = "/rest/v2/submission-vocabulary/";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        EngineTestSupport.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theVocabularyIsServedAsYaml() throws Exception {
        mvc.perform(get(BASE + "1.2.0/vocabulary/vocabulary.yaml"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/yaml;charset=UTF-8"))
            .andExpect(header().string("Cache-Control", "max-age=86400, public"))
            .andExpect(content().string(containsString("version: 1.2.0")));
    }

    @ParameterizedTest
    @CsvSource({
        "LICENSE, text/plain;charset=UTF-8",
        "README.md, text/markdown;charset=UTF-8",
        "examples/v3-example/instance.json, application/json;charset=UTF-8",
        "examples/v3-example/expected/LCS-99999-01.mzTab, text/tab-separated-values;charset=UTF-8",
        "mappings/output/mztab-2.0.0-M.yaml, application/yaml;charset=UTF-8"})
    void filesAreServedWithTheirType(String path, String type) throws Exception {
        mvc.perform(get(BASE + "1.2.0/" + path))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", type))
            .andExpect(header().string("Cache-Control", "max-age=86400, public"));
    }

    @Test
    void anotherVersionIsNotFound() throws Exception {
        mvc.perform(get(BASE + "1.2.0/vocabulary/vocabulary.yaml")).andExpect(status().isOk());
        mvc.perform(get(BASE + "0.9.0/vocabulary/vocabulary.yaml")).andExpect(status().isNotFound());
    }

    @Test
    void aPublishedPathWithoutAFileIsNotFound() throws Exception {
        mvc.perform(get(BASE + "1.2.0/vocabulary/vocabulary.yaml")).andExpect(status().isOk());
        mvc.perform(get(BASE + "1.2.0/vocabulary/none.yaml")).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"examples/v3-example", "examples/v3-example/expected"})
    void aDirectoryIsNotFound(String path) throws Exception {
        mvc.perform(get(BASE + "1.2.0/" + path)).andExpect(status().isNotFound())
            .andExpect(header().string("Cache-Control", not(containsString("public"))));
    }

    /** The firewall of Spring Security answers the dot segments first; the controller's own check is tested below. */
    @ParameterizedTest
    @ValueSource(strings = {"vocabulary/../../pom.xml", "src/x", "pom.xml", "vocabulary/../LICENSE", "mappings/x.yaml",
        "vocabulary/./vocabulary.yaml", "vocabulary/a b"})
    void otherPathsAreRefused(String path) throws Exception {
        mvc.perform(get(BASE + "1.2.0/" + path)).andExpect(status().isBadRequest());
    }
}
