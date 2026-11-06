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

import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * The engine's application context as the shared tests use it: one job slot,
 * and no requests to the Ontology Lookup Service. Subclasses with the same
 * configuration share one cached context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "engine.max-concurrent-jobs=1")
@AutoConfigureMockMvc
abstract class EngineContextTest {

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @Autowired
    MockMvc mvc;

    /** The versions this engine accepts; the first is used to make fixtures of this engine's line. */
    @Value("${engine.mztab-versions}")
    String[] mzTabVersions;

    /** A fixture rewritten to declare this engine's first accepted version. */
    byte[] ofThisLine(String resource) {
        return Fixtures.withVersion(Fixtures.resource(resource), mzTabVersions[0].strip());
    }
}
