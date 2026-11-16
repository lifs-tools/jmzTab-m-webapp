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

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** {@code POST /rest/v2/working-copies/new} and {@code GET .../still-missing}, with the real mzTab-M 2.0 engine. */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class WorkingCopiesNewFileApiTest {

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

    @AfterEach
    void forward() {
        ENGINE.reset();
    }

    private static String newFile(String version, String contentProfile, String profileId, String profileVersion,
        String mzTabId) {
        return "{\"mzTabVersion\":\"" + version + "\",\"contentProfile\":"
            + (contentProfile == null ? "null" : "\"" + contentProfile + "\"") + ",\"profileId\":\"" + profileId
            + "\",\"profileVersion\":\"" + profileVersion + "\",\"mzTabId\":\"" + mzTabId + "\"}";
    }

    private ResultActions postNew(String body) throws Exception {
        return mvc.perform(post(BASE + "/new").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void createsANewCopy() throws Exception {
        MvcResult r = postNew(newFile("2.0.0-M", null, "mztab-m-2.0-default", "1.1.0", "my-study"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.origin").value("new"))
            .andExpect(jsonPath("$.fileName").value("my-study.mzTab"))
            .andExpect(jsonPath("$.mzTabVersion").value("2.0.0-M"))
            .andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.validationProfile.kind").value("CATALOG"))
            .andExpect(jsonPath("$.validationProfile.id").value("mztab-m-2.0-default"))
            .andExpect(jsonPath("$.validationProfile.version").value("1.1.0"))
            .andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("id").asText();
        assertEquals("/rest/v2/working-copies/" + id, URI.create(r.getResponse().getHeader("Location")).getPath());

        mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.origin").value("new"));
        mvc.perform(post(BASE).param("validation", id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get(BASE + "/" + id + "/lines").param("from", "2").param("count", "1"))
            .andExpect(jsonPath("$[0].cells[2]").value("my-study"));
    }

    @Test
    void newIsNotAnId() throws Exception {
        // only POST /new is the new-file route; GET /new is the /{id} route with an id that is not a UUID
        mvc.perform(get(BASE + "/new")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("id must be a UUID."));
    }

    @Test
    void refusalsAre422WithTheReason() throws Exception {
        postNew(newFile("9.9.0-M", null, "mztab-m-2.0-default", "1.1.0", "no-engine"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value(containsString("9.9.0-M")));
        postNew(newFile("2.0.0-M", null, "mztab-m-2.1-default", "1.0.0", "other-version"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value(containsString("mztab-m-2.1-default")));
        postNew(newFile("2.0.0-M", "M", "mztab-m-2.0-default", "1.1.0", "content-profile"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value(containsString("content profile")));
        postNew(newFile("2.0.0-M", null, "mztab-m-2.0-default", "1.1.0", "bad id"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value(containsString("mzTab-ID")));
        postNew(newFile("2.0.0-M", null, "no-such-rules", "1.0.0", "unknown-rules"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value(containsString("no-such-rules")));
    }

    @Test
    void unreadableBodiesAre400() throws Exception {
        postNew("{\"mzTabVersion\":\"2.0.0-M\"}").andExpect(status().isBadRequest());
        postNew("{\"mzTabVersion\":\"2.0.0-M\",\"contentProfile\":null,\"profileId\":\"mztab-m-2.0-default\","
            + "\"profileVersion\":\"1.1.0\",\"mzTabId\":\"x\",\"extra\":1}").andExpect(status().isBadRequest());
        postNew("not json").andExpect(status().isBadRequest());
        postNew("").andExpect(status().isBadRequest());
    }

    @Test
    void aBusyOrUnavailableEngineIs503() throws Exception {
        ENGINE.mode(EngineProxy.Mode.BUSY);
        postNew(newFile("2.0.0-M", null, "mztab-m-2.0-default", "1.1.0", "busy"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().exists("Retry-After"));
        ENGINE.mode(EngineProxy.Mode.DOWN);
        postNew(newFile("2.0.0-M", null, "mztab-m-2.0-default", "1.1.0", "down"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value(containsString("temporarily unavailable")));
    }

    @Test
    void stillMissing() throws Exception {
        MvcResult r = postNew(newFile("2.0.0-M", null, "mztab-m-2.0-default", "1.1.0", "missing"))
            .andExpect(status().isCreated()).andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("id").asText();

        JsonNode missing = json.readTree(mvc.perform(get(BASE + "/" + id + "/still-missing"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revision").value(0))
            .andExpect(jsonPath("$.checked").value(false))
            .andReturn().getResponse().getContentAsString());
        assertEquals("MUST", missing.at("/missing/0/requirement").asText());

        mvc.perform(get(BASE + "/" + UUID.randomUUID() + "/still-missing")).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/not-a-uuid/still-missing")).andExpect(status().isBadRequest());
    }
}
