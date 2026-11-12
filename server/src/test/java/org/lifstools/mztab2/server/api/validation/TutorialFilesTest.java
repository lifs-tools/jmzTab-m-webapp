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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.webapp.ExampleFileConfig;
import org.lifstools.mztab.validator.webapp.domain.ExampleFile;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

/**
 * The bundled teaching files and their step lists, as the real {@code examples.properties} configures them. The
 * engine tests ({@code TutorialExamplesTwoZeroTest}, {@code TutorialExamplesTwoOneTest}) check that the files
 * validate; this test checks the catalog entries, the licence line and the steps.
 */
class TutorialFilesTest {

    static final String LIPIDOMICS_20 = "teaching-lipidomics-2.0.mztab";
    static final String LIPIDOMICS_21 = "teaching-lipidomics-2.1.mztab";
    static final String STUDY_DESIGN_21 = "study-design-2.1.mztab";
    static final String ORIGINALS = "https://github.com/HUPO-PSI/mzTab-M/blob/main/";

    static List<ExampleFile> tutorials;
    static TutorialCatalog catalog;

    @BeforeAll
    static void bindTheRealConfiguration() throws IOException {
        // as the application: WebConfig's @PropertySource and its ExampleFileConfig bean
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:examples.properties"));
        ExampleFileConfig config = ExampleFileConfig.bind(environment);
        tutorials = config.getExampleFile().stream().filter(f -> "tutorial".equals(f.getSection())).toList();
        // the production constructor: the class path root tutorial/, as the application reads it
        catalog = new TutorialCatalog(config, new ObjectMapper());
    }

    static ExampleFile entry(String name) {
        return tutorials.stream().filter(f -> name.equals(f.getName())).findFirst().orElseThrow(
            () -> new AssertionError("no tutorial example " + name));
    }

    static List<TourStepDto> steps(String name) {
        return catalog.tour(name).orElseThrow(() -> new AssertionError("no tour for " + name)).steps();
    }

    @Test
    void everyTutorialExampleHasAValidTour() {
        assertEquals(Set.of(LIPIDOMICS_20, LIPIDOMICS_21, STUDY_DESIGN_21),
            tutorials.stream().map(ExampleFile::getName).collect(Collectors.toSet()));
        for (ExampleFile f : tutorials) {
            assertEquals(URI.create("classpath:tutorial/" + f.getName()), f.getUri(), f.getName());
            assertTrue(catalog.content(f.getName()).isPresent(), f.getName());
            assertFalse(steps(f.getName()).isEmpty(), f.getName());
        }
    }

    @Test
    void entriesNameVersionsOriginalsAndCounterparts() {
        assertEntry(LIPIDOMICS_20, "2.0", "2.0.0-M", "examples/2.0/manual_null_null_lipidomics.mztab");
        assertEntry(LIPIDOMICS_21, "2.1", "2.1.0-M", "examples/2.1/manual_null_null_lipidomics_v2.1.mztab");
        assertEntry(STUDY_DESIGN_21, "2.1", "2.1.0-M", "examples/2.1/example_study_variable_group.mztab");
        assertEquals(LIPIDOMICS_21, catalog.tour(LIPIDOMICS_20).orElseThrow().counterpart());
        assertEquals(LIPIDOMICS_20, catalog.tour(LIPIDOMICS_21).orElseThrow().counterpart());
        assertNull(catalog.tour(STUDY_DESIGN_21).orElseThrow().counterpart());
    }

    private static void assertEntry(String name, String version, String mzTabVersion, String original) {
        ExampleFile f = entry(name);
        assertEquals(version, f.getVersion(), name);
        assertEquals(mzTabVersion, f.getMzTabVersion(), name);
        assertEquals(URI.create(ORIGINALS + original), f.getOriginalUri(), name);
        assertEquals(URI.create("classpath:tutorial/" + name.replace(".mztab", ".tour.json")), f.getTour(), name);
        String content = catalog.content(name).orElseThrow();
        assertTrue(content.contains("\tmzTab-version\t" + mzTabVersion + "\n"), name);
    }

    @Test
    void firstCommentNamesTheOriginal() {
        assertFalse(tutorials.isEmpty());
        for (ExampleFile f : tutorials) {
            String first = catalog.content(f.getName()).orElseThrow().lines().findFirst().orElseThrow();
            String original = f.getOriginalUri().toString().substring(ORIGINALS.length());
            assertTrue(first.startsWith("COM\t"), first);
            assertTrue(first.contains("HUPO-PSI/mzTab-M"), first);
            assertTrue(first.contains(original), first);
            assertTrue(first.contains("adapted"), first);
            assertTrue(first.contains("CC BY 4.0"), first);
        }
    }

    @Test
    void lipidomicsToursCoverEveryChapter() {
        Set<String> chapters20 = Set.of("overview", "metadata", "summary", "features", "evidence", "references",
            "cv-parameters");
        assertTrue(chapters(LIPIDOMICS_20).containsAll(chapters20), chapters(LIPIDOMICS_20).toString());
        Set<String> chapters21 = new HashSet<>(chapters20);
        chapters21.add("profile");
        assertTrue(chapters(LIPIDOMICS_21).containsAll(chapters21), chapters(LIPIDOMICS_21).toString());
        assertEquals(Set.of("study-design"), chapters(STUDY_DESIGN_21));
    }

    private static Set<String> chapters(String name) {
        return steps(name).stream().map(TourStepDto::chapter).collect(Collectors.toSet());
    }

    /** The step ids and chapters that the chapter pages, the tour and the versions page consume. */
    @Test
    void lipidomicsToursHaveTheStepsInOrder() {
        List<String> ids20 = List.of("sections", "what-file", "measured", "samples-runs-assays", "groups",
            "units-vocabularies", "summary-row", "features", "evidence", "one-lipid", "validate-yours");
        List<String> chapters20 = List.of("overview", "metadata", "metadata", "metadata", "metadata",
            "cv-parameters", "summary", "features", "evidence", "references", "overview");
        assertEquals(ids20, steps(LIPIDOMICS_20).stream().map(TourStepDto::id).toList());
        assertEquals(chapters20, steps(LIPIDOMICS_20).stream().map(TourStepDto::chapter).toList());

        List<String> ids21 = new ArrayList<>(ids20);
        ids21.add(10, "profile");
        List<String> chapters21 = new ArrayList<>(chapters20);
        chapters21.add(10, "profile");
        assertEquals(ids21, steps(LIPIDOMICS_21).stream().map(TourStepDto::id).toList());
        assertEquals(chapters21, steps(LIPIDOMICS_21).stream().map(TourStepDto::chapter).toList());

        assertEquals("validate-yours", steps(STUDY_DESIGN_21).get(steps(STUDY_DESIGN_21).size() - 1).id());
        assertNull(steps(LIPIDOMICS_20).get(0).target());
        assertNull(steps(LIPIDOMICS_20).get(ids20.size() - 1).target());
    }

    @Test
    void stepTargetsAreTheBriefedElements() {
        for (String name : List.of(LIPIDOMICS_20, LIPIDOMICS_21)) {
            Map<String, TourTargetDto> targets = new HashMap<>();
            steps(name).stream().filter(s -> s.target() != null).forEach(s -> targets.put(s.id(), s.target()));
            assertEquals(new TourTargetDto("metadata", "general", null, null, null, null), targets.get("what-file"));
            assertEquals(new TourTargetDto("metadata", "instrument", null, null, null, null), targets.get("measured"));
            assertEquals(new TourTargetDto("metadata", "assay", null, null, null, null),
                targets.get("samples-runs-assays"));
            assertEquals(new TourTargetDto("metadata", "study_variable", null, null, null, null),
                targets.get("groups"));
            assertEquals(new TourTargetDto("metadata", null, "small_molecule-quantification_unit", null, null, null),
                targets.get("units-vocabularies"));
            assertEquals(new TourTargetDto("summary", null, null, 0, null, null), targets.get("summary-row"));
            assertEquals(new TourTargetDto("features", null, null, null, "SMF_ID", true), targets.get("features"));
            assertEquals(new TourTargetDto("evidence", null, null, 0, null, null), targets.get("evidence"));
            assertEquals(new TourTargetDto("summary", null, null, 0, "SMF_ID_REFS", null), targets.get("one-lipid"));
        }
        assertEquals(new TourTargetDto("metadata", null, "mzTab-profile", null, null, null),
            steps(LIPIDOMICS_21).get(10).target());
    }

    /** "one-lipid" follows a lipid from its summary row to its evidence: lines from an SML to an SME row. */
    @Test
    void oneLipidSpansSummaryToEvidence() {
        for (String name : List.of(LIPIDOMICS_20, LIPIDOMICS_21)) {
            List<String> lines = catalog.content(name).orElseThrow().lines().toList();
            TourStepDto step = steps(name).stream().filter(s -> "one-lipid".equals(s.id())).findFirst().orElseThrow();
            assertTrue(lines.get(step.lines().get(0) - 1).startsWith("SML\t"), name);
            assertTrue(lines.get(step.lines().get(1) - 1).startsWith("SME\t"), name);
            assertTrue(step.lookFor() != null && !step.lookFor().isBlank(), name);
        }
    }
}
