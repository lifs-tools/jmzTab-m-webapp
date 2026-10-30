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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The OLS stub answers the two requests the engine's OLS client makes (the
 * webapp has no OLS client of its own): a term by OBO id, and the
 * hierarchical parents behind the term's link.
 */
class OlsStubTest {

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private OlsStub stub;

    @BeforeEach
    void start() throws Exception {
        stub = new OlsStub();
    }

    @AfterEach
    void stop() throws Exception {
        stub.close();
    }

    @Test
    void aFixtureTermIsFound() throws Exception {
        HttpResponse<String> response = get("/ols/api/ontologies/MS/terms?obo_id=MS:1000073");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode term = json.readTree(response.body()).at("/_embedded/terms/0");
        assertThat(term.path("label").asText()).isEqualTo("electrospray ionization");
        assertThat(term.path("obo_id").asText()).isEqualTo("MS:1000073");
        assertThat(term.at("/_links/hierarchicalParents/href").asText())
            .isEqualTo("http://" + stub.hostAndPort() + "/ols/api/ontologies/MS/terms/MS_1000073/hierarchicalParents");
        assertThat(stub.requests()).containsExactly("GET /ols/api/ontologies/MS/terms?obo_id=MS:1000073");
        assertThat(stub.unmatched()).isEmpty();
    }

    @Test
    void theParentsOfAFixtureTermAreAllItsAncestors() throws Exception {
        HttpResponse<String> response = get("/ols/api/ontologies/MS/terms/MS_1000073/hierarchicalParents");

        assertThat(response.statusCode()).isEqualTo(200);
        List<String> parents = new ArrayList<>();
        json.readTree(response.body()).at("/_embedded/terms").forEach(t -> {
            parents.add(t.path("obo_id").asText());
            // no parent links: the client stops walking at each of them
            assertThat(t.at("/_links/hierarchicalParents").isMissingNode()).isTrue();
        });
        assertThat(parents).containsExactly("MS:0000000", "MS:1000008", "MS:1000458", "MS:1000463", "MS:1001458");
        assertThat(stub.unmatched()).isEmpty();
    }

    @Test
    void aTermWithoutFixtureIsNotFound() throws Exception {
        HttpResponse<String> response = get("/ols/api/ontologies/MS/terms?obo_id=MS:9999999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo(OlsStub.NOT_FOUND);
        assertThat(stub.unmatched()).containsExactly("GET /ols/api/ontologies/MS/terms?obo_id=MS:9999999");
    }

    @Test
    void aTermOfAnotherOntologyIsNotFound() throws Exception {
        assertThat(get("/ols/api/ontologies/UO/terms?obo_id=MS:1000073").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://" + stub.hostAndPort() + pathAndQuery)).GET()
            .build(), HttpResponse.BodyHandlers.ofString());
    }
}
