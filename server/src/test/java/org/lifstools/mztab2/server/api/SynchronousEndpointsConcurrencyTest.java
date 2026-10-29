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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.Application;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousLimiter;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * At most rest.synchronous.max-concurrent synchronous validations and
 * conversions run at once; a request that finds no free slot within
 * rest.synchronous.wait answers 503.
 */
@SpringBootTest(classes = Application.class, properties = {"eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false", "rest.synchronous.max-concurrent=1", "rest.synchronous.wait=50ms"})
@ActiveProfiles("microservice")
@AutoConfigureMockMvc
class SynchronousEndpointsConcurrencyTest {

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

    @Autowired
    SynchronousLimiter limiter;

    @ParameterizedTest
    @EnumSource(SynchronousEndpoint.class)
    void answers503WhileAllSlotsAreTaken(SynchronousEndpoint endpoint) throws Exception {
        try (SynchronousLimiter.Permit held = limiter.acquire()) {
            mvc.perform(endpoint.request())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(SynchronousLimiter.BUSY));
        }
    }

    @ParameterizedTest
    @EnumSource(SynchronousEndpoint.class)
    void aFinishedRequestFreesItsSlot(SynchronousEndpoint endpoint) throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(endpoint.request())
                .andExpect(status().is(oneOf(200, 422)));
        }
    }
}
