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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** {@code GET .../imports/kinds} and {@code POST .../imports}, with the real mzTab-M 2.0 engine. */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class WorkingCopiesImportApiTest {

    private static final String BASE = "/rest/v2/working-copies";
    static final EngineProxy ENGINE = EngineProxy.start();

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        ENGINE.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    EngineRegistry registry;

    private String id;

    @BeforeEach
    void newCopy() throws Exception {
        String body = "{\"mzTabVersion\":\"2.0.0-M\",\"contentProfile\":null,\"profileId\":\"mztab-m-2.0-default\","
            + "\"profileVersion\":\"1.1.0\",\"mzTabId\":\"import-api\"}";
        id = json.readTree(mvc.perform(post(BASE + "/new").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        // what the engine takes is known before it is busy
        registry.refresh("2.0");
    }

    @AfterEach
    void forward() {
        ENGINE.reset();
    }

    private ResultActions postImport(String copy, String body) throws Exception {
        return mvc.perform(post(BASE + "/" + copy + "/imports").contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private static String request(String kind, long revision, String instance) {
        return "{\"kind\":\"" + kind + "\",\"revision\":" + revision + ",\"instance\":" + instance
            + ",\"studyId\":\"LCS-99999\",\"keepExisting\":true}";
    }

    @Test
    void kindsOfACopy() throws Exception {
        String kinds = mvc.perform(get(BASE + "/" + id + "/imports/kinds")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals("[\"vocabulary-instance\"]", kinds);
        mvc.perform(get(BASE + "/" + UUID.randomUUID() + "/imports/kinds")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/x/imports/kinds")).andExpect(status().isBadRequest());
    }

    @Test
    void anInstanceOverFiveMiBIs413BeforeItIsParsed() throws Exception {
        // not JSON: it would be 400 if it were parsed
        String big = "x".repeat(5 * 1024 * 1024 + 1);
        postImport(id, big).andExpect(status().isPayloadTooLarge());
        postImport(id, request("vocabulary-instance", 0, "{\"datasets\":[{}],\"pad\":\"" + "x".repeat(5 * 1024 * 1024)
            + "\"}")).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void anUnknownKindIs400() throws Exception {
        postImport(id, request("workbook", 0, "{\"datasets\":[{}]}")).andExpect(status().isBadRequest());
    }

    @Test
    void unreadableRequestsAre400() throws Exception {
        postImport(id, "not json").andExpect(status().isBadRequest());
        postImport(id, "{\"kind\":\"vocabulary-instance\"}").andExpect(status().isBadRequest());
        postImport("x", request("vocabulary-instance", 0, "{}")).andExpect(status().isBadRequest());
    }

    @Test
    void datasetsAreChecked() throws Exception {
        postImport(id, request("vocabulary-instance", 0, "{\"datasets\":[{},{},{}]}").replace("\"keepExisting",
            "\"datasetIndex\":4,\"keepExisting")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("dataset 4 of 3"));
        postImport(id, request("vocabulary-instance", 0, "{\"datasets\":[{},{}]}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("choose one of 2 datasets"));
    }

    @Test
    void aStaleRevisionIs409AndAMissingCopyIs404() throws Exception {
        postImport(id, request("vocabulary-instance", 7, "{\"datasets\":[{}]}")).andExpect(status().isConflict());
        postImport(UUID.randomUUID().toString(), request("vocabulary-instance", 0, "{\"datasets\":[{}]}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void aBusyEngineIs503WithRetryAfter() throws Exception {
        ENGINE.mode(EngineProxy.Mode.BUSY);
        ENGINE.retryAfterSeconds(7);
        postImport(id, request("vocabulary-instance", 0, "{\"datasets\":[{}]}"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "7"))
            .andExpect(jsonPath("$.message").value(containsString("Too many files")));
    }

    @Test
    void anEngineThatIsDownIs503() throws Exception {
        ENGINE.mode(EngineProxy.Mode.DOWN);
        postImport(id, request("vocabulary-instance", 0, "{\"datasets\":[{}]}"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void aReplacingPreviewWithASetCellWithoutAHeaderAppliesThroughTheEditsEndpoint() throws Exception {
        com.fasterxml.jackson.databind.JsonNode instance;
        try (java.io.InputStream in = getClass().getClassLoader()
            .getResourceAsStream("submission-vocabulary/examples/v3-example/instance.json")) {
            instance = json.readTree(in);
        }
        // keepExisting=false: the copy's own mzTab-ID (import-api) is replaced, which is a SetCell with no header
        String body = request("vocabulary-instance", 0, json.writeValueAsString(instance)).replace(
            "\"keepExisting\":true", "\"datasetIndex\":1,\"keepExisting\":false");
        String preview = postImport(id, body).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString();
        com.fasterxml.jackson.databind.JsonNode batch = json.readTree(preview).get("batch");
        boolean setCell = false;
        for (com.fasterxml.jackson.databind.JsonNode op : batch.get("operations")) {
            if ("SetCell".equals(op.get("op").asText())) {
                setCell = true;
                assertEquals(true, op.get("header") == null || op.get("header").isNull(), op.toString());
            }
        }
        assertEquals(true, setCell, "the preview replaces a value: " + batch.get("operations"));

        // the batch as the SPA posts it: JSON round trip
        mvc.perform(post(BASE + "/" + id + "/edits").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(batch))).andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(1));
    }
}
