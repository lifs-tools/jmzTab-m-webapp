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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * A local, deterministic stand-in for the Ontology Lookup Service, which the
 * engine started by {@link EngineTestSupport} asks during semantic validation.
 * It answers only the two requests the engine's OLS client makes, from the
 * fixtures in {@code src/test/resources/ols-stub/terms/}, one file per term,
 * named by its short form (e.g. {@code MS_1000073.json}):
 *
 * <ul>
 * <li>{@code GET /ols/api/ontologies/{ontology}/terms?obo_id={id}}: the term,
 * with a {@code hierarchicalParents} link back to this stub;</li>
 * <li>{@code GET /ols/api/ontologies/{ontology}/terms/{shortForm}/hierarchicalParents}:
 * all hierarchical ancestors of the term at once, without parent links. The
 * client walks parent links until a term has none, so a flat list of all
 * ancestors gives the same answer to "is X a child of Y" as walking the
 * hierarchy level by level.</li>
 * </ul>
 *
 * Every other request, and a term without a fixture, gets {@code 404} with
 * the JSON body of OLS4 for an unknown resource; OLS4 answers an unknown
 * {@code obo_id} the same way. Such requests are listed by {@link #unmatched()}.
 *
 * <p>The fixtures hold the terms the server test files use, with label, IRI,
 * OBO id and ontology prefix as OLS4 ({@code https://www.ebi.ac.uk/ols4/api})
 * returned them on 2026-10-30 for the term and its {@code hierarchicalAncestors}.
 * A test file that uses a new term needs a new fixture; until then the engine
 * reports the term as not retrievable.
 */
public final class OlsStub implements AutoCloseable {

    /** The body of OLS4's 404 answer. */
    static final String NOT_FOUND = "{\"status\":404,\"message\":\"The requested resource was not found\"}";

    private static final Pattern TERMS = Pattern.compile("/ols/api/ontologies/([^/]+)/terms");
    private static final Pattern PARENTS = Pattern.compile(
        "/ols/api/ontologies/([^/]+)/terms/([A-Za-z0-9_]+)/hierarchicalParents");
    private static final Pattern OBO_ID = Pattern.compile("[A-Za-z0-9]+:[A-Za-z0-9_]+");

    private final ObjectMapper json = new ObjectMapper();
    private final MockWebServer server = new MockWebServer();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> unmatched = new CopyOnWriteArrayList<>();

    /** Starts the stub on a free port of the loopback interface. */
    public OlsStub() throws IOException {
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return answer(request);
            }
        });
        server.start(InetAddress.getLoopbackAddress(), 0);
    }

    /** {@code host:port}, as the engine's {@code engine.ols.host} expects it. */
    public String hostAndPort() {
        return InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getPort();
    }

    /** Every request so far, as {@code METHOD path?query}, in arrival order. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** The requests answered with {@code 404}. */
    public List<String> unmatched() {
        return List.copyOf(unmatched);
    }

    @Override
    public void close() throws IOException {
        server.shutdown();
    }

    private MockResponse answer(RecordedRequest request) {
        String line = request.getMethod() + " " + request.getPath();
        requests.add(line);
        HttpUrl url = request.getRequestUrl();
        MockResponse response = null;
        if (url != null && "GET".equals(request.getMethod())) {
            Matcher parents = PARENTS.matcher(url.encodedPath());
            Matcher terms = TERMS.matcher(url.encodedPath());
            if (parents.matches()) {
                response = ancestors(parents.group(1), parents.group(2));
            } else if (terms.matches() && url.queryParameter("obo_id") != null) {
                response = term(terms.group(1), url.queryParameter("obo_id"));
            }
        }
        if (response == null) {
            unmatched.add(line);
            System.err.println("OLS stub: 404 for " + line + " (no fixture in src/test/resources/ols-stub/terms/)");
            response = new MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                .setBody(NOT_FOUND);
        }
        return response;
    }

    private MockResponse term(String ontology, String oboId) {
        if (!OBO_ID.matcher(oboId).matches()) {
            return null;
        }
        String shortForm = oboId.replace(':', '_');
        JsonNode fixture = fixture(shortForm);
        if (fixture == null || !fixture.at("/term/ontology_prefix").asText().equalsIgnoreCase(ontology)) {
            return null;
        }
        ObjectNode term = term(fixture.get("term"), ontology);
        term.putObject("_links").putObject("hierarchicalParents").put("href",
            "http://" + hostAndPort() + "/ols/api/ontologies/" + ontology + "/terms/" + shortForm
            + "/hierarchicalParents");
        return termQuery(List.of(term));
    }

    private MockResponse ancestors(String ontology, String shortForm) {
        JsonNode fixture = fixture(shortForm);
        if (fixture == null) {
            return null;
        }
        List<ObjectNode> ancestors = new ArrayList<>();
        fixture.get("ancestors").forEach(ancestor -> {
            ObjectNode term = term(ancestor, ontology);
            // a link object without hierarchicalParents: the client stops walking here
            term.putObject("_links").putObject("self").put("href", "http://" + hostAndPort()
                + "/ols/api/ontologies/" + ontology + "/terms?obo_id=" + ancestor.get("obo_id").asText());
            ancestors.add(term);
        });
        return termQuery(ancestors);
    }

    /** A term as OLS returns it within {@code ontology}, without {@code _links}. */
    private ObjectNode term(JsonNode fixture, String ontology) {
        ObjectNode term = fixture.deepCopy();
        String oboId = fixture.get("obo_id").asText();
        term.put("short_form", oboId.replace(':', '_'));
        term.put("ontology_name", ontology.toLowerCase(Locale.ROOT));
        term.put("is_obsolete", false);
        term.put("is_defining_ontology", oboId.startsWith(fixture.get("ontology_prefix").asText() + ":"));
        return term;
    }

    private MockResponse termQuery(List<ObjectNode> terms) {
        ObjectNode body = json.createObjectNode();
        ArrayNode array = body.putObject("_embedded").putArray("terms");
        terms.forEach(array::add);
        body.putObject("page").put("size", terms.size()).put("totalElements", terms.size())
            .put("totalPages", 1).put("number", 0);
        try {
            return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(json.writeValueAsString(body));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode fixture(String shortForm) {
        try (InputStream in = OlsStub.class.getResourceAsStream("/ols-stub/terms/" + shortForm + ".json")) {
            return in == null ? null : json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
