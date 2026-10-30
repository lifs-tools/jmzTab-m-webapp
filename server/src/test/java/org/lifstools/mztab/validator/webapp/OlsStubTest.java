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
package org.lifstools.mztab.validator.webapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;
import uk.ac.ebi.pride.utilities.ols.web.service.config.OLSWsConfig;
import uk.ac.ebi.pride.utilities.ols.web.service.model.Identifier;
import uk.ac.ebi.pride.utilities.ols.web.service.model.Term;

/** The OLS stub answers the ols-client library as the engine uses it. */
class OlsStubTest {

    private OlsStub stub;
    private OLSClient client;

    @BeforeEach
    void start() throws Exception {
        stub = new OlsStub();
        client = new OLSClient(new OLSWsConfig("http", stub.hostAndPort() + "/ols/"));
    }

    @AfterEach
    void stop() throws Exception {
        stub.close();
    }

    @Test
    void aFixtureTermIsFound() {
        Term term = client.getTermById(new Identifier("MS:1000073", Identifier.IdentifierType.OBO), "MS");

        assertThat(term.getLabel()).isEqualTo("electrospray ionization");
        assertThat(term.getOboId().getIdentifier()).isEqualTo("MS:1000073");
        assertThat(stub.requests()).containsExactly("GET /ols/api/ontologies/MS/terms?obo_id=MS:1000073");
        assertThat(stub.unmatched()).isEmpty();
    }

    @Test
    void theParentsOfAFixtureTermAreAllItsAncestors() {
        List<Term> parents = client.getTermParents(new Identifier("MS:1000073", Identifier.IdentifierType.OBO),
            "MS", -1);

        assertThat(parents).extracting(t -> t.getOboId().getIdentifier())
            .containsExactly("MS:0000000", "MS:1000008", "MS:1000458", "MS:1000463", "MS:1001458");
        assertThat(stub.requests()).containsExactly(
            "GET /ols/api/ontologies/MS/terms?obo_id=MS:1000073",
            "GET /ols/api/ontologies/MS/terms/MS_1000073/hierarchicalParents");
        assertThat(stub.unmatched()).isEmpty();
    }

    @Test
    void aTermWithoutFixtureIsNotFound() {
        assertThatThrownBy(() -> client.getTermById(new Identifier("MS:9999999", Identifier.IdentifierType.OBO),
            "MS")).isInstanceOf(HttpClientErrorException.NotFound.class);
        assertThat(stub.unmatched()).containsExactly("GET /ols/api/ontologies/MS/terms?obo_id=MS:9999999");
    }

    @Test
    void aTermOfAnotherOntologyIsNotFound() {
        assertThatThrownBy(() -> client.getTermById(new Identifier("MS:1000073", Identifier.IdentifierType.OBO),
            "UO")).isInstanceOf(HttpClientErrorException.NotFound.class);
    }
}
