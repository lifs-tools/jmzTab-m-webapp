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

import org.junit.jupiter.api.extension.ExtendWith;
import org.lifstools.mztab.validator.webapp.EngineTestSupport;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.session.StorageFullException;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class ExamplesApiControllerTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        EngineTestSupport.register(registry);
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @MockitoBean
    ExampleFetcher fetcher;

    @MockitoSpyBean
    ValidationSessionService sessions;

    @Autowired
    MockMvc mvc;

    static final String ID = "lipidomics-example.mzTab";

    @Test
    void listsExamplesWithSections() throws Exception {
        mvc.perform(get("/rest/v2/examples")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='lipidomics-example.mzTab')].section").value("example"))
            .andExpect(jsonPath("$[?(@.id=='mzTab_2_0-M_mapping.xml')].section").value("mapping"));
    }

    @Test
    void validatesExampleById() throws Exception {
        Path tmp = Files.createTempFile("ex", ".tmp");
        try (var in = ExamplesApiControllerTest.class.getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            Files.write(tmp, in.readAllBytes());
        }
        when(fetcher.download(any())).thenReturn(tmp);
        mvc.perform(post("/rest/v2/examples/" + ID + "/validations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"level\":\"INFO\",\"maxErrors\":10}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.fileName").value(ID));
        verify(fetcher).download(URI.create(
            "https://raw.githubusercontent.com/HUPO-PSI/mzTab/master/examples/2_0-Metabolomics_Release/lipidomics-example.mzTab"));
        assertFalse(Files.exists(tmp));
    }

    @Test
    void emptyBodyUsesDefaults() throws Exception {
        Path tmp = Files.createTempFile("ex", ".tmp");
        Files.writeString(tmp, "MTD\tmzTab-version\t2.0.0-M\n");
        when(fetcher.download(any())).thenReturn(tmp);
        mvc.perform(post("/rest/v2/examples/" + ID + "/validations").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.maxErrors").value(100));
    }

    @Test
    void maxErrorsOutOfRangeIs400() throws Exception {
        mvc.perform(post("/rest/v2/examples/" + ID + "/validations").contentType(MediaType.APPLICATION_JSON)
            .content("{\"maxErrors\":501}")).andExpect(status().isBadRequest());
    }

    @Test
    void fetchFailureIs502() throws Exception {
        when(fetcher.download(any())).thenThrow(new ExampleFetcher.ExampleFetchException("boom"));
        mvc.perform(post("/rest/v2/examples/" + ID + "/validations").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.message").value("The example file could not be downloaded."));
    }

    @Test
    void storageFullIs503() throws Exception {
        Path tmp = Files.createTempFile("ex", ".tmp");
        when(fetcher.download(any())).thenReturn(tmp);
        org.mockito.Mockito.doThrow(new StorageFullException()).when(sessions).submit(any(), any(), any(), any());
        try {
            mvc.perform(post("/rest/v2/examples/" + ID + "/validations").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The validator's storage is full. Please try again later."));
        } finally {
            org.mockito.Mockito.reset(sessions);
        }
        assertFalse(Files.exists(tmp));
    }

    @Test
    void mappingExampleCannotBeValidated() throws Exception {
        mvc.perform(post("/rest/v2/examples/mzTab_2_0-M_mapping.xml/validations").contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Mapping files cannot be validated."));
    }

    @Test
    void unknownExampleIs404() throws Exception {
        mvc.perform(post("/rest/v2/examples/nope/validations").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Unknown example."));
    }
}
