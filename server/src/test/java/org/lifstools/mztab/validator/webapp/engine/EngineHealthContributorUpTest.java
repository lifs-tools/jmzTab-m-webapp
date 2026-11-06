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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.NamedContributor;

class EngineHealthContributorUpTest {

    @Test
    void reachableEngineIsUpWithInfoDetailsAndRegistryIsRefreshed() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
                "{\"line\":\"2.0\",\"mzTabVersions\":[\"2.0.0-M\"],\"jmztabmVersion\":\"1.0.8\",\"build\":\"b\","
                + "\"maxUploadBytes\":10,\"maxConcurrentJobs\":2}"));
            EngineRegistry registry = new EngineRegistry(new EnginesProperties("2.0",
                List.of(new EnginesProperties.Engine("2.0", server.url("/").uri())), Duration.ofSeconds(2),
                Duration.ofMinutes(1), null));
            EngineHealthContributor contributor = new EngineHealthContributor(registry);
            NamedContributor<HealthContributor> named = contributor.iterator().next();
            assertEquals("engine-2-0", named.getName());
            Health health = ((HealthIndicator) named.getContributor()).health();
            assertEquals("UP", health.getStatus().getCode());
            assertEquals("1.0.8", health.getDetails().get("jmztabmVersion"));
            assertEquals("1.0.8", registry.lastKnownInfo().get("2.0").orElseThrow().jmztabmVersion());
        }
    }
}
