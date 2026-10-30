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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * Guards the committed OpenAPI file that the TypeScript client is generated
 * from. Fails when the file differs from the live spec; with
 * {@code -Dopenapi.update=true} it rewrites the file instead.
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
class OpenApiSpecExportTest {

    static final String STALE = "OpenAPI spec is stale: run ./mvnw -pl server -am test -Dfrontend.skip=true -Dtest=OpenApiSpecExportTest -Dopenapi.update=true";

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void committedSpecMatchesLiveSpec() throws Exception {
        String body = mvc.perform(get("/v3/api-docs/mztab-m-api"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode live = sort(MAPPER.readTree(body));
        if (live instanceof ObjectNode obj) {
            // the servers entry carries the request's origin (e.g. http://localhost), not part of the contract
            obj.remove("servers");
        }
        String expected = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(live)
                .replace("\r\n", "\n") + "\n";

        Path file = specFile();
        if (Boolean.parseBoolean(System.getProperty("openapi.update"))) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(file), STALE);
        String actual = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(expected, actual, STALE);
    }

    private static Path specFile() {
        // surefire sets basedir to the module directory; its working directory is under target/
        String base = System.getProperty("basedir", System.getProperty("user.dir"));
        Path frontend = Path.of(base).resolve("../frontend").normalize();
        assertTrue(Files.isDirectory(frontend),
                "Expected the frontend module at " + frontend + " (resolved from " + base + ") but it does not exist");
        return frontend.resolve("openapi/mztab-m-api.json");
    }

    /** Recursively sorts object keys; array order is preserved. */
    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String n : names) {
                out.set(n, sort(node.get(n)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(e -> out.add(sort(e)));
            return out;
        }
        return node;
    }
}
