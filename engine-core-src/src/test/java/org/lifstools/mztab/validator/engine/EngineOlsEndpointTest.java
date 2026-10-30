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
package org.lifstools.mztab.validator.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;
import uk.ac.ebi.pride.utilities.ols.web.service.config.OLSWsConfig;

/**
 * The Ontology Lookup Service endpoint of semantic validation comes from
 * {@code engine.ols.*}; without them it is the library's default endpoint.
 */
class EngineOlsEndpointTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(EngineConfig.class);

    @Test
    void withoutPropertiesTheClientUsesTheLibraryDefaultEndpoint() {
        OLSWsConfig libraryDefault = new OLSWsConfig();
        runner.run(context -> {
            OLSClient client = context.getBean(OLSClient.class);
            assertThat(client.getConfig().getProtocol()).isEqualTo(libraryDefault.getProtocol());
            assertThat(client.getConfig().getHostName()).isEqualTo(libraryDefault.getHostName());
        });
    }

    @Test
    void theConfiguredProtocolHostAndBasePathAreUsed() {
        runner.withPropertyValues(
                "engine.ols.protocol=http",
                "engine.ols.host=127.0.0.1:4567",
                "engine.ols.base-path=ols-stub")
            .run(context -> {
                OLSClient client = context.getBean(OLSClient.class);
                assertThat(client.getConfig().getProtocol()).isEqualTo("http");
                assertThat(client.getConfig().getHostName()).isEqualTo("127.0.0.1:4567/ols-stub/");
            });
    }

    @Test
    void aBasePathOfMoreThanOneSegmentIsRejectedAtStartup() {
        // the client splits the host name at '/' and keeps only the first path segment
        runner.withPropertyValues("engine.ols.base-path=a/b")
            .run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("engine.ols.base-path"));
    }
}
