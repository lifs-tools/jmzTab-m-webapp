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

import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.EngineTestSupport;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The synchronous legacy endpoints keep nothing in storage: their files would
 * otherwise stay for days and count against the storage cap.
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ActiveProfiles("microservice")
@AutoConfigureMockMvc
class LegacyValidateStorageTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        EngineTestSupport.register(registry);
    }


    @Autowired
    MockMvc mvc;

    @Autowired
    StorageService storage;

    @Test
    void validatePlainLeavesNoSessionBehind() throws Exception {
        Set<UUID> before = sessions();

        mvc.perform(post("/rest/v2/validatePlain").param("semanticValidation", "true")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(resource("/metabolomics/minimal-m-2.0.mztab")))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$").isArray());

        assertEquals(before, sessions());
    }

    @Test
    void brokenPlainFileLeavesNoSessionBehind() throws Exception {
        Set<UUID> before = sessions();

        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content("this is not an mzTab file"))
            .andExpect(status().is(oneOf(200, 422)));

        assertEquals(before, sessions());
    }

    @Test
    void validateLeavesNoSessionBehind() throws Exception {
        Set<UUID> before = sessions();

        mvc.perform(post("/rest/v2/validate")
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .content(toJson("/metabolomics/lipidomics-example.mzTab")))
            .andExpect(status().is(oneOf(200, 422)))
            .andExpect(jsonPath("$").isArray());

        assertEquals(before, sessions());
    }

    private Set<UUID> sessions() {
        return storage.sessions().collect(Collectors.toSet());
    }

    /** The JSON model of a fixture, as the engine converts it. */
    private byte[] toJson(String resource) throws Exception {
        return mvc.perform(post("/rest/v2/convertPlain")
                .contentType(ConvertApiIntegrationTest.TSV).accept(MediaType.APPLICATION_JSON)
                .content(resource(resource)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }
}
