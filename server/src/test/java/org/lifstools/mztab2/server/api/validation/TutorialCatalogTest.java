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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.webapp.ExampleFileConfig;
import org.lifstools.mztab.validator.webapp.domain.ExampleFile;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The fixtures live in {@code tutorial-fixture/}, read as the catalog's {@code tutorial/}. */
class TutorialCatalogTest {

    static final String ROOT = "tutorial-fixture/";
    static final String ID = "fixture-2.0.mztab";

    static ExampleFile example(String name, String uri, String tour) {
        ExampleFile f = new ExampleFile();
        f.setName(name);
        f.setSection("tutorial");
        f.setMzTabVersion("2.0.0-M");
        f.setUri(URI.create(uri));
        f.setTour(tour == null ? null : URI.create(tour));
        return f;
    }

    static ExampleFileConfig config(ExampleFile... files) {
        ExampleFileConfig config = new ExampleFileConfig();
        config.setExampleFile(List.of(files));
        return config;
    }

    static TutorialCatalog catalog(ExampleFile... files) {
        return new TutorialCatalog(config(files), new ObjectMapper(), ROOT);
    }

    @Test
    void servesContentAndTour() {
        TutorialCatalog catalog = catalog(example(ID, "classpath:tutorial/fixture-2.0.mztab",
            "classpath:tutorial/fixture-2.0.tour.json"));
        String content = catalog.content(ID).orElseThrow();
        assertTrue(content.startsWith("COM\tA fixture"), content);
        TourDto tour = catalog.tour(ID).orElseThrow();
        assertEquals(ID, tour.exampleId());
        assertEquals("The fixture tour", tour.title());
        assertEquals("fixture-2.1.mztab", tour.counterpart());
        assertEquals(List.of("sections", "what-the-file-is", "instrument", "summary-row", "features", "evidence"),
            tour.steps().stream().map(TourStepDto::id).toList());
        TourStepDto evidence = tour.steps().get(5);
        assertEquals(new TourTargetDto("evidence", null, null, 2, "chemical_name", null), evidence.target());
        assertEquals(List.of(20, 23), evidence.lines());
        assertEquals("the mzTab-ID", tour.steps().get(1).lookFor());
    }

    /** Bound as the application binds {@code examples.properties}: the classpath URIs arrive unchanged. */
    @Test
    void readsBoundConfiguration() {
        // with a property editor initializer, as the application's binder: URIs go through Spring's URIEditor
        ExampleFileConfig bound = new Binder(List.of(new MapConfigurationPropertySource(Map.of(
            "example.exampleFile[0].name", ID,
            "example.exampleFile[0].uri", "classpath:tutorial/fixture-2.0.mztab",
            "example.exampleFile[0].tour", "classpath:tutorial/fixture-2.0.tour.json",
            "example.exampleFile[0].originalUri", "https://example.org/original.mztab"))), null, null, editors -> {
            }).bind("example", ExampleFileConfig.class).get();
        assertEquals(URI.create("classpath:tutorial/fixture-2.0.mztab"), bound.getExampleFile().get(0).getUri());
        TutorialCatalog catalog = new TutorialCatalog(bound, new ObjectMapper(), ROOT);
        assertTrue(catalog.content(ID).isPresent());
        assertTrue(catalog.tour(ID).isPresent());
    }

    @Test
    void servesContentWithoutTour() {
        TutorialCatalog catalog = catalog(example(ID, "classpath:tutorial/fixture-2.0.mztab", null));
        assertTrue(catalog.content(ID).isPresent());
        assertEquals(Optional.empty(), catalog.tour(ID));
    }

    @Test
    void failsStartupListingEveryProblem() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> catalog(
            example(ID, "classpath:tutorial/fixture-2.0.mztab", "classpath:tutorial/fixture-2.0-bad.tour.json")));
        assertTrue(e.getMessage().contains("bad-key"), e.getMessage());
        assertTrue(e.getMessage().contains("bad-lines"), e.getMessage());
        assertTrue(e.getMessage().contains(ID), e.getMessage());
    }

    @Test
    void failsStartupOnMissingFiles() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> catalog(
            example("missing.mztab", "classpath:tutorial/missing.mztab", null),
            example(ID, "classpath:tutorial/fixture-2.0.mztab", "classpath:tutorial/missing.tour.json")));
        assertTrue(e.getMessage().contains("missing.mztab"), e.getMessage());
        assertTrue(e.getMessage().contains("missing.tour.json"), e.getMessage());
    }

    @Test
    void failsStartupOnClasspathOutsideTutorial() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> catalog(
            example("outside.mztab", "classpath:application.properties", null),
            example(ID, "classpath:tutorial/fixture-2.0.mztab", "classpath:tutorial/../fixture-2.0.tour.json")));
        assertTrue(e.getMessage().contains("outside.mztab"), e.getMessage());
        assertTrue(e.getMessage().contains(ID), e.getMessage());
    }

    @Test
    void failsStartupOnUnknownTourFields() {
        // the example id comes from the example entry, never from the tour file
        assertThrows(IllegalStateException.class, () -> new TutorialCatalog(config(
            example(ID, "classpath:tutorial/fixture-2.0.mztab", "classpath:tutorial/fixture-2.0-extra-field.tour.json")),
            new ObjectMapper(), ROOT));
    }

    @Test
    void failsStartupOnTourOfRemoteExample() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> catalog(
            example("remote.mztab", "https://example.org/remote.mztab", "classpath:tutorial/fixture-2.0.tour.json")));
        assertTrue(e.getMessage().contains("remote.mztab"), e.getMessage());
    }

    @Test
    void failsStartupOnOriginalUriThatIsNotHttps() {
        ExampleFile f = example(ID, "classpath:tutorial/fixture-2.0.mztab", null);
        f.setOriginalUri(URI.create("http://example.org/original.mztab"));
        assertThrows(IllegalStateException.class, () -> catalog(f));
    }

    @Test
    void failsStartupOnDuplicateNames() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> catalog(
            example(ID, "classpath:tutorial/fixture-2.0.mztab", null),
            example(ID, "https://example.org/fixture-2.0.mztab", null)));
        assertTrue(e.getMessage().contains(ID), e.getMessage());
    }

    @Test
    void failsStartupOnNamesThatAreNoPathSegment() {
        for (String name : List.of("..", "a b.mztab", "a/b.mztab", "x".repeat(101))) {
            assertThrows(IllegalStateException.class,
                () -> catalog(example(name, "classpath:tutorial/fixture-2.0.mztab", null)), name);
        }
    }

    @Test
    void ignoresRemoteExamples() {
        TutorialCatalog catalog = catalog(example("MTBLS263.mztab", "https://example.org/MTBLS263.mztab", null));
        assertEquals(Optional.empty(), catalog.content("MTBLS263.mztab"));
        assertEquals(Optional.empty(), catalog.tour("MTBLS263.mztab"));
    }

    @Test
    void bootsWithoutExamples() {
        TutorialCatalog catalog = catalog();
        assertEquals(Optional.empty(), catalog.content(ID));
    }
}
