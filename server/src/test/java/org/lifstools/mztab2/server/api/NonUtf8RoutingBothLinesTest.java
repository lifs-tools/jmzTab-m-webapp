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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.VersionFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * F1 on an instance with engines for both lines (stubs that record what they
 * get): a file that declares {@code 2.1.0-M} and has one ISO-8859-1 byte after
 * the version line goes to the 2.1 engine, on the synchronous and on the
 * asynchronous path, and never to the 2.0 engine of the default line.
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(AwaitAsyncValidations.class)
class NonUtf8RoutingBothLinesTest {

    static final StubEngine ENGINE_20 = new StubEngine("2.0");
    static final StubEngine ENGINE_21 = new StubEngine("2.1");

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        registry.add("engines.default-line", () -> "2.0");
        registry.add("engines.mztabm[0].line", () -> "2.0");
        registry.add("engines.mztabm[0].url", ENGINE_20::url);
        registry.add("engines.mztabm[1].line", () -> "2.1");
        registry.add("engines.mztabm[1].url", ENGINE_21::url);
    }

    @AfterAll
    static void stop() throws IOException {
        ENGINE_20.server.shutdown();
        ENGINE_21.server.shutdown();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void clear() {
        ENGINE_20.validations.clear();
        ENGINE_21.validations.clear();
    }

    @Test
    void validatePlainSendsTheFileToTheEngineOfItsLine() throws Exception {
        mvc.perform(post("/rest/v2/validatePlain")
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .content(VersionFixtures.LATIN1_21))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].message").value("validated by the 2.1 engine"));

        assertThat(ENGINE_21.validations).hasSize(1);
        assertThat(ENGINE_20.validations).as("a 2.1 file must never reach the 2.0 engine").isEmpty();
    }

    @Test
    void asynchronousValidationSendsTheFileToTheEngineOfItsLine() throws Exception {
        JsonNode done = validate(multipart("/rest/v2/validations").file(upload()).param("level", "INFO"));

        assertThat(done.get("status").asText()).as(done.toString()).isEqualTo("FINISHED");
        assertThat(done.get("engine").asText()).startsWith("mzTab-M 2.1 · jmzTab-m ");
        assertThat(done.get("defaultVersionApplied").asBoolean()).isFalse();
        assertThat(done.get("mzTabVersion").asText()).isEqualTo("2.1.0-M");
        assertThat(ENGINE_21.validations).hasSize(1);
        assertThat(ENGINE_20.validations).as("a 2.1 file must never reach the 2.0 engine").isEmpty();
    }

    /** The profile the SPA preselects for such a file (it reads 2.1.0-M too) is of the file's line. */
    @Test
    void theDefaultProfileOfTheLineIsAccepted() throws Exception {
        JsonNode done = validate(multipart("/rest/v2/validations").file(upload()).param("level", "INFO")
            .param("validationProfile", "mztab-m-2.1-default").param("validationProfileVersion", "1.0.0"));

        assertThat(done.get("status").asText()).as(done.toString()).isEqualTo("FINISHED");
        assertThat(ENGINE_21.validations).hasSize(1);
        assertThat(ENGINE_20.validations).isEmpty();
    }

    private static MockMultipartFile upload() {
        return new MockMultipartFile("file", "latin1.mztab", "text/plain", VersionFixtures.LATIN1_21);
    }

    private JsonNode validate(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        MvcResult created = mvc.perform(request).andExpect(status().isAccepted()).andReturn();
        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            JsonNode n = json.readTree(mvc.perform(get("/rest/v2/validations/" + id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
            String status = n.get("status").asText();
            if ("FINISHED".equals(status) || "FAILED".equals(status)) {
                return n;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("validation did not finish");
    }

    /** An engine of one line: its info, and one warning for every validation. */
    static final class StubEngine {

        final MockWebServer server = new MockWebServer();
        final List<RecordedRequest> validations = new CopyOnWriteArrayList<>();

        StubEngine(String line) {
            String version = line + ".0-M";
            String info = "{\"line\":\"" + line + "\",\"mzTabVersions\":[\"" + version + "\"],\"jmztabmVersion\":\""
                + line + ".0\",\"build\":\"stub\",\"maxUploadBytes\":67108864,\"maxConcurrentJobs\":2}";
            String result = "{\"type\":\"outcome\",\"parsed\":true,\"mzTabVersion\":\"" + version + "\",\"messages\":"
                + "[{\"code\":\"c\",\"category\":\"format\",\"message_type\":\"warn\",\"message\":\"validated by the "
                + line + " engine\",\"line_number\":3}]}\n"
                + "{\"type\":\"metadata\",\"line\":2,\"key\":\"mzTab-version\",\"value\":\"" + version + "\"}\n"
                + "{\"type\":\"end\",\"sections\":{\"metadataLines\":1}}\n";
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath() == null ? "" : request.getPath();
                    if (path.startsWith("/engine/v1/info")) {
                        return new MockResponse().setHeader("Content-Type", "application/json").setBody(info);
                    }
                    if (path.startsWith("/engine/v1/validations")) {
                        validations.add(request);
                        return new MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody(result);
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            try {
                server.start(InetAddress.getLoopbackAddress(), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String url() {
            return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getPort();
        }
    }
}
