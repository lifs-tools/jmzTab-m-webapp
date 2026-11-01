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

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.ExampleFileConfig;
import org.lifstools.mztab.validator.webapp.config.WebSecurityConfig;
import org.lifstools.mztab.validator.webapp.domain.ExampleFile;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.session.ValidationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The bundled tutorial examples on an instance with the 2.0 engine only. The fixtures live in
 * {@code tutorial-fixture/}, read as {@code tutorial/}.
 */
@WebMvcTest(controllers = ExamplesApiController.class)
@ContextConfiguration(classes = {ExamplesApiController.class, RestErrorAdvice.class, WebSecurityConfig.class,
    ExamplesApiControllerTutorialTest.Config.class})
class ExamplesApiControllerTutorialTest {

    static final String ROOT = "tutorial-fixture/";
    static final String ID = "fixture-2.0.mztab";
    /** A 2.1 tutorial example; its content is the 2.0 fixture, which is all this test needs. */
    static final String ID_21 = "fixture-2.1.mztab";
    static final String REMOTE = "MTBLS263.mztab";
    static final String ORIGINAL = "https://raw.githubusercontent.com/HUPO-PSI/mzTab/master/examples/fixture.mzTab";

    /**
     * Not a {@code @TestConfiguration}: the application's own component scan has no type exclude filter and would
     * pick it up in every {@code @SpringBootTest}. Its {@code @Bean} methods count through
     * {@code @ContextConfiguration} only.
     */
    static class Config {

        @Bean
        ExampleFileConfig exampleFileConfig() {
            ExampleFile bundled = example(ID, "2.0.0-M", "classpath:tutorial/fixture-2.0.mztab");
            bundled.setTour(URI.create("classpath:tutorial/fixture-2.0.tour.json"));
            bundled.setOriginalUri(URI.create(ORIGINAL));
            ExampleFile bundled21 = example(ID_21, "2.1.0-M", "classpath:tutorial/fixture-2.0.mztab");
            ExampleFile remote = example(REMOTE, "2.0.0-M", "https://example.org/MTBLS263.mztab");
            remote.setSection("example");
            ExampleFileConfig config = new ExampleFileConfig();
            config.setExampleFile(List.of(bundled, bundled21, remote));
            return config;
        }

        @Bean
        TutorialCatalog tutorialCatalog(ExampleFileConfig config, ObjectMapper mapper) {
            return new TutorialCatalog(config, mapper, ROOT);
        }

        @Bean
        ExampleFetcher exampleFetcher() {
            return new ExampleFetcher(HttpClient.newHttpClient(), ExampleFetcher.MAX_BYTES, ExampleFetcher.DEADLINE,
                false, ROOT);
        }

        static ExampleFile example(String name, String mzTabVersion, String uri) {
            ExampleFile f = new ExampleFile();
            f.setName(name);
            f.setSection("tutorial");
            f.setDescription("A fixture");
            f.setVersion(mzTabVersion.substring(0, 3));
            f.setMzTabVersion(mzTabVersion);
            f.setUri(URI.create(uri));
            return f;
        }
    }

    @MockitoBean
    EngineRegistry engines;

    @MockitoBean
    ValidationSessionService sessions;

    @MockitoSpyBean
    ExampleFetcher fetcher;

    @Autowired
    MockMvc mvc;

    @AfterEach
    void verifyNothingRemoteWasFetched() throws Exception {
        verify(fetcher, never()).download(URI.create("https://example.org/MTBLS263.mztab"));
    }

    void onlyLine20() {
        when(engines.lines()).thenReturn(Set.of("2.0"));
    }

    @Test
    void contentIsPlainText() throws Exception {
        onlyLine20();
        mvc.perform(get("/rest/v2/examples/" + ID + "/content")).andExpect(status().isOk())
            .andExpect(content().contentType("text/plain;charset=UTF-8"))
            .andExpect(content().string(startsWith("COM\t")));
    }

    @Test
    void tourHasStepsAndExampleId() throws Exception {
        onlyLine20();
        mvc.perform(get("/rest/v2/examples/" + ID + "/tour")).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.exampleId").value(ID))
            .andExpect(jsonPath("$.title").value("The fixture tour"))
            .andExpect(jsonPath("$.counterpart").value(ID_21))
            .andExpect(jsonPath("$.steps.length()").value(6))
            .andExpect(jsonPath("$.steps[1].target.key").value("mzTab-ID"))
            .andExpect(jsonPath("$.steps[1].lines[0]").value(2))
            .andExpect(jsonPath("$.steps[1].lines[1]").value(4));
    }

    @Test
    void tourOfBundledExampleWithoutTourIs404() throws Exception {
        onlyLine20();
        mvc.perform(get("/rest/v2/examples/" + ID_21 + "/tour")).andExpect(status().isNotFound());
    }

    @Test
    void contentOfRemoteExampleIs404WithoutFetching() throws Exception {
        onlyLine20();
        mvc.perform(get("/rest/v2/examples/" + REMOTE + "/content").accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Unknown example."));
        mvc.perform(get("/rest/v2/examples/" + REMOTE + "/tour")).andExpect(status().isNotFound());
        mvc.perform(get("/rest/v2/examples/nope/content")).andExpect(status().isNotFound());
        mvc.perform(get("/rest/v2/examples/nope/tour")).andExpect(status().isNotFound());
        verify(fetcher, never()).download(any());
    }

    @Test
    void idOutsidePatternIs400() throws Exception {
        onlyLine20();
        // URI, not a template string: MockMvc would encode the % again, and the firewall would answer, not the check
        mvc.perform(get(URI.create("/rest/v2/examples/a%20b/tour"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(startsWith("Invalid value for parameter 'id'")));
        for (String id : List.of("a%20b", "a".repeat(101), "a:b", "a%2Cb", "%C3%A9")) {
            mvc.perform(get(URI.create("/rest/v2/examples/" + id + "/content"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("Invalid value for parameter 'id'")));
            mvc.perform(get(URI.create("/rest/v2/examples/" + id + "/tour"))).andExpect(status().isBadRequest());
            mvc.perform(post(URI.create("/rest/v2/examples/" + id + "/validations"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        }
        verify(fetcher, never()).download(any());
    }

    @Test
    void contentDoesNotDependOnEngines() throws Exception {
        onlyLine20();
        mvc.perform(get("/rest/v2/examples/" + ID_21 + "/content")).andExpect(status().isOk())
            .andExpect(content().string(startsWith("COM\t")));
        mvc.perform(get("/rest/v2/examples")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + ID_21 + "')]").isEmpty());
        mvc.perform(post("/rest/v2/examples/" + ID_21 + "/validations").contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andExpect(status().isNotFound());
        when(engines.lines()).thenReturn(Set.of());
        mvc.perform(get("/rest/v2/examples/" + ID + "/tour")).andExpect(status().isOk());
    }

    @Test
    void listMarksBundledExamples() throws Exception {
        onlyLine20();
        mvc.perform(get("/rest/v2/examples")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].id").value(ID))
            .andExpect(jsonPath("$[0].tour").value(true))
            .andExpect(jsonPath("$[0].uri").value("rest/v2/examples/" + ID + "/content"))
            .andExpect(jsonPath("$[0].originalUri").value(ORIGINAL))
            .andExpect(jsonPath("$[1].id").value(REMOTE))
            .andExpect(jsonPath("$[1].tour").value(false))
            .andExpect(jsonPath("$[1].uri").value("https://example.org/MTBLS263.mztab"))
            .andExpect(jsonPath("$[1].originalUri").doesNotExist());
    }

    @Test
    void validatesBundledExample() throws Exception {
        onlyLine20();
        AtomicReference<String> submitted = new AtomicReference<>();
        when(sessions.submit(eq(ID), any(), any(), any())).thenAnswer(inv -> {
            try (InputStream in = inv.getArgument(1)) {
                submitted.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            return new ValidationRecord(UUID.randomUUID(), ID, ValidationStatus.QUEUED, null, ValidationLevel.INFO,
                100, null, null, Instant.now(), null, null, null, null, null, null, null, null, false, null);
        });
        try {
            mvc.perform(post("/rest/v2/examples/" + ID + "/validations").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.fileName").value(ID));
            verify(fetcher).download(URI.create("classpath:tutorial/fixture-2.0.mztab"));
            org.junit.jupiter.api.Assertions.assertTrue(submitted.get().startsWith("COM\tA fixture"));
        } finally {
            reset(sessions);
        }
    }
}
