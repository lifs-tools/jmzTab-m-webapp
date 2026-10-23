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
package org.lifstools.mztab.validator.webapp.sections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab.validator.webapp.session.SectionsSummary;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
import org.lifstools.mztab.validator.webapp.session.ValidationOutcome;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.session.ValidationStatus;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/** Section store and paging on real validation sessions; OLS is mocked. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class SectionStoreTest {

    private static final String LIPIDOMICS = "/metabolomics/lipidomics-example.mzTab";
    private static final String NO_SMF_SME
        = "/metabolomics/publication-samples/lipidomics-example-missing-feature-and-evidence.mzTab";
    private static final String MOUSE_LIVER = "/metabolomics/MouseLiver_negative.mzTab";
    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @Autowired
    ValidationSessionService sessions;

    @Autowired
    SectionReader reader;

    @Test
    void writesHeaderAndRowsWithMzTabColumnNames() throws Exception {
        UUID id = finished(LIPIDOMICS).id();

        TablePage page = reader.table(id, Section.SML, query(0, 10)).orElseThrow();

        assertEquals("SML_ID", page.allColumns().get(0));
        assertTrue(page.allColumns().contains("abundance_assay[1]"), page.allColumns().toString());
        assertTrue(page.totalRows() > 0);
        assertEquals(0, page.rows().get(0).index());
    }

    @Test
    void absentTablesHaveNoSize() throws Exception {
        ValidationRecord record = finished(NO_SMF_SME);

        SectionsSummary summary = record.sections();
        assertNotNull(summary);
        assertNotNull(summary.sml());
        assertNull(summary.smf());
        assertNull(summary.sme());
        assertTrue(summary.metadataLines() > 0);
        assertTrue(reader.table(record.id(), Section.SMF, query(0, 10)).isEmpty());
        assertTrue(reader.table(record.id(), Section.SME, query(0, 10)).isEmpty());
    }

    @Test
    void summaryMatchesTables() throws Exception {
        ValidationRecord record = finished(LIPIDOMICS);

        TablePage sml = reader.table(record.id(), Section.SML, query(0, 1)).orElseThrow();
        assertEquals(record.sections().sml().rows(), sml.totalRows());
        assertEquals(record.sections().sml().columns(), sml.allColumns().size());
    }

    @Test
    void unparseableFileHasNoSections() throws Exception {
        UUID id = sessions.submit("bad.mzTab", stream("this is not mzTab"), STRUCTURAL, null).id();

        ValidationRecord record = awaitFinal(id);

        assertNull(record.sections());
        assertEquals(ValidationOutcome.NOT_PARSED, record.outcome());
        assertTrue(reader.table(id, Section.SML, query(0, 10)).isEmpty());
        assertTrue(reader.metadata(id, "general", 0, 10).isEmpty());
    }

    @Test
    void defaultColumnWindowIsFifty() throws Exception {
        UUID id = finished(MOUSE_LIVER).id();

        TablePage page = reader.table(id, Section.SML, query(0, 5)).orElseThrow();

        assertEquals(Math.min(50, page.allColumns().size()), page.columns().size());
        assertEquals(page.allColumns().subList(0, page.columns().size()), page.columns());
        assertEquals(5, page.rows().size());
        page.rows().forEach(r -> assertEquals(page.columns().size(), r.cells().size()));
    }

    @Test
    void columnSelectionFollowsRequestOrder() throws Exception {
        UUID id = finished(LIPIDOMICS).id();
        TableQuery q = new TableQuery(0, 3, null, false, List.of(), List.of("SML_ID", "SML_ID"));
        TablePage page = reader.table(id, Section.SML, q).orElseThrow();

        assertEquals(List.of("SML_ID", "SML_ID"), page.columns());
        assertEquals(page.rows().get(0).cells().get(0), page.rows().get(0).cells().get(1));
    }

    @Test
    void sortsNumericallyWithNullsLast() throws Exception {
        UUID id = finished(MOUSE_LIVER).id();
        TablePage all = reader.table(id, Section.SML, query(0, 1)).orElseThrow();
        String abundance = all.allColumns().stream().filter(c -> c.startsWith("abundance_assay["))
            .findFirst().orElseThrow();

        for (boolean descending : new boolean[]{false, true}) {
            TableQuery q = new TableQuery(0, 200, abundance, descending, List.of(), List.of(abundance));
            TablePage page = reader.table(id, Section.SML, q).orElseThrow();
            List<String> cells = page.rows().stream().map(r -> r.cells().get(0)).toList();
            boolean seenNull = false;
            Double previous = null;
            for (String cell : cells) {
                if (cell.isEmpty() || cell.equals("null")) {
                    seenNull = true;
                    continue;
                }
                assertFalse(seenNull, "null cell before a value");
                double value = Double.parseDouble(cell);
                if (previous != null) {
                    assertTrue(descending ? previous >= value : previous <= value, previous + " " + value);
                }
                previous = value;
            }
            assertNotNull(previous);
        }
    }

    @Test
    void filterThenPage() throws Exception {
        UUID id = finished(MOUSE_LIVER).id();
        List<String> expected = new ArrayList<>();
        for (int p = 0;; p++) {
            TablePage page = reader.table(id, Section.SML, new TableQuery(p, 200, null, false, List.of(),
                List.of("SML_ID"))).orElseThrow();
            page.rows().forEach(r -> {
                if (r.cells().get(0).toLowerCase().contains("1")) {
                    expected.add(r.cells().get(0));
                }
            });
            if ((p + 1) * 200L >= page.totalRows()) {
                break;
            }
        }
        assertTrue(expected.size() >= 4);

        TableQuery q = new TableQuery(1, 2, null, false, List.of(new ColumnFilter("SML_ID", "1")),
            List.of("SML_ID"));
        TablePage page = reader.table(id, Section.SML, q).orElseThrow();

        assertEquals(expected.size(), page.totalRows());
        assertEquals(expected.subList(2, 4), page.rows().stream().map(r -> r.cells().get(0)).toList());
    }

    @Test
    void filterIsCaseInsensitive() throws Exception {
        UUID id = finished(LIPIDOMICS).id();
        TablePage first = reader.table(id, Section.SML, query(0, 1)).orElseThrow();
        String chemicalName = first.rows().get(0).cells().get(first.allColumns().indexOf("chemical_name"));
        TableQuery q = new TableQuery(0, 10, null, false,
            List.of(new ColumnFilter("chemical_name", chemicalName.toUpperCase())), List.of());

        assertTrue(reader.table(id, Section.SML, q).orElseThrow().totalRows() >= 1);
    }

    @Test
    void unknownColumnIsRejected() throws Exception {
        UUID id = finished(LIPIDOMICS).id();

        assertThrows(IllegalArgumentException.class, () -> reader.table(id, Section.SML,
            new TableQuery(0, 10, null, false, List.of(), List.of("nope"))));
        assertThrows(IllegalArgumentException.class, () -> reader.table(id, Section.SML,
            new TableQuery(0, 10, "nope", false, List.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> reader.table(id, Section.SML,
            new TableQuery(0, 10, null, false, List.of(new ColumnFilter("nope", "x")), List.of())));
    }

    @Test
    void pageSizeIsBounded() throws Exception {
        UUID id = finished(LIPIDOMICS).id();

        assertThrows(IllegalArgumentException.class, () -> reader.table(id, Section.SML, query(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> reader.table(id, Section.SML, query(0, 201)));
        assertThrows(IllegalArgumentException.class, () -> reader.table(id, Section.SML, query(-1, 10)));
    }

    @Test
    void metadataGroupsFollowFileOrder() throws Exception {
        UUID id = finished(LIPIDOMICS).id();

        List<MetadataGroup> groups = reader.metadataGroups(id);

        assertEquals("general", groups.get(0).name());
        MetadataGroup assay = groups.stream().filter(g -> g.name().equals("assay")).findFirst().orElseThrow();
        MetadataPage all = reader.metadata(id, "assay", 0, 200).orElseThrow();
        assertEquals(assay.count(), all.total());
        assertEquals(assay.count(), all.entries().size());
        assertTrue(all.entries().stream().allMatch(e -> e.key().startsWith("assay[")));
        assertTrue(all.entries().get(0).lineNumber() < all.entries().get(all.entries().size() - 1).lineNumber());
        MetadataPage second = reader.metadata(id, "assay", 1, 1).orElseThrow();
        assertEquals(all.entries().get(1), second.entries().get(0));
    }

    @Test
    void deletedSessionStopsServingCachedData() throws Exception {
        UUID id = finished(LIPIDOMICS).id();
        assertTrue(reader.table(id, Section.SML, query(0, 1)).isPresent());

        sessions.delete(id);

        assertTrue(reader.table(id, Section.SML, query(0, 1)).isEmpty());
        assertTrue(reader.metadata(id, "general", 0, 10).isEmpty());
    }

    @Test
    void largeFilePagesQuickly() throws Exception {
        UUID id = finished(MOUSE_LIVER).id();
        reader.table(id, Section.SML, query(0, 50)).orElseThrow();

        Instant start = Instant.now();
        for (int i = 0; i < 20; i++) {
            TableQuery q = new TableQuery(i, 50, i % 2 == 0 ? "SML_ID" : null, false,
                List.of(new ColumnFilter("SML_ID", "1")), List.of());
            reader.table(id, Section.SML, q).orElseThrow();
        }

        assertTrue(Duration.between(start, Instant.now()).compareTo(Duration.ofSeconds(2)) < 0);
    }

    private static TableQuery query(int page, int size) {
        return new TableQuery(page, size, null, false, List.of(), List.of());
    }

    private ValidationRecord finished(String resource) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return awaitFinal(sessions.submit("f.mzTab", in, STRUCTURAL, null).id());
        }
    }

    private ValidationRecord awaitFinal(UUID id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            ValidationRecord record = sessions.find(id).orElseThrow();
            if (record.status() == ValidationStatus.FINISHED || record.status() == ValidationStatus.FAILED) {
                return record;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("validation " + id + " did not finish within 90 s");
    }

    private static InputStream stream(String text) throws IOException {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
