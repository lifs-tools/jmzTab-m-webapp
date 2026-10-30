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
package org.lifstools.mztab.validator.webapp.engine;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Path;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F5: the URL of the 2.0 engine points at an engine of mzTab-M 2.1 (as with
 * swapped URLs). That engine is reported unavailable in health and app info;
 * the correctly configured 2.1 engine is up.
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false", "ga.id="})
@AutoConfigureMockMvc
class EngineOfAnotherLineTest {

    static final MockWebServer ENGINE_21 = start();

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + ENGINE_21.getPort();
        registry.add("storage.location", () -> storageRoot.toString());
        registry.add("engines.mztabm[0].line", () -> "2.0");
        registry.add("engines.mztabm[0].url", () -> url);
        registry.add("engines.mztabm[1].line", () -> "2.1");
        registry.add("engines.mztabm[1].url", () -> url);
    }

    @AfterAll
    static void stop() throws IOException {
        ENGINE_21.shutdown();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    EngineRegistry registry;

    @Test
    void engineOfAnotherLineIsUnavailableInHealth() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.engines.components.engine-2-0.status").value("UNAVAILABLE"))
            .andExpect(jsonPath("$.components.engines.components.engine-2-1.status").value("UP"));
    }

    @Test
    void engineOfAnotherLineIsUnavailableInAppInfo() throws Exception {
        registry.refresh();

        mvc.perform(get("/rest/v2/app-info")).andExpect(status().isOk())
            .andExpect(jsonPath("$.engines[0].line").value("2.0"))
            .andExpect(jsonPath("$.engines[0].available").value(false))
            .andExpect(jsonPath("$.engines[0].jmztabmVersion").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.engines[1].line").value("2.1"))
            .andExpect(jsonPath("$.engines[1].available").value(true));
    }

    private static MockWebServer start() {
        MockWebServer server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/engine/v1/info")) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                        "{\"line\":\"2.1\",\"mzTabVersions\":[\"2.1.0-M\"],\"jmztabmVersion\":\"2.1.0\","
                        + "\"build\":\"b\",\"maxUploadBytes\":67108864,\"maxConcurrentJobs\":2}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            server.start(InetAddress.getLoopbackAddress(), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return server;
    }
}
