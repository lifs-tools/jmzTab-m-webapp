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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.EngineTestSupport;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
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

/** The table cache is weighted by estimated bytes and capped in entries. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class SectionCacheTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        EngineTestSupport.register(registry);
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @Autowired
    ValidationSessionService sessions;

    @Autowired
    StorageService storage;

    @Test
    void newReaderStartsEmpty() {
        SectionReader reader = new SectionReader(storage, 268435456L);
        assertEquals(0, reader.cachedTables());
        assertEquals(0, reader.cachedBytes());
    }

    @Test
    void evictsLeastRecentlyUsedTablesToFitTheByteBudget() throws Exception {
        ValidationRecord first = finished();
        ValidationRecord second = finished();
        long bytes = weigh(first);
        assertTrue(bytes > 1);
        SectionReader reader = new SectionReader(storage, bytes + bytes / 2);

        reader.table(first.id(), Section.SML, query()).orElseThrow();
        assertEquals(1, reader.cachedTables());
        assertEquals(bytes, reader.cachedBytes());

        reader.table(second.id(), Section.SML, query()).orElseThrow();
        assertEquals(1, reader.cachedTables(), "the first table must have been evicted");
        assertEquals(bytes, reader.cachedBytes());
        // the evicted table is still served (reloaded)
        assertEquals(first.sections().sml().rows(),
            reader.table(first.id(), Section.SML, query()).orElseThrow().totalRows());
    }

    @Test
    void keepsTablesWhileTheyFit() throws Exception {
        ValidationRecord first = finished();
        ValidationRecord second = finished();
        long bytes = weigh(first);
        SectionReader reader = new SectionReader(storage, bytes * 2);

        reader.table(first.id(), Section.SML, query()).orElseThrow();
        reader.table(second.id(), Section.SML, query()).orElseThrow();

        assertEquals(2, reader.cachedTables());
        assertEquals(2 * bytes, reader.cachedBytes());
    }

    @Test
    void tableOverTheBudgetIsServedButNotCached() throws Exception {
        ValidationRecord record = finished();
        SectionReader reader = new SectionReader(storage, weigh(record) - 1);

        TablePage page = reader.table(record.id(), Section.SML, query()).orElseThrow();

        assertEquals(record.sections().sml().rows(), page.totalRows());
        assertTrue(page.rows().size() > 0);
        assertEquals(0, reader.cachedTables());
        assertEquals(0, reader.cachedBytes());
    }

    @Test
    void fewHugeCellsWeighMoreThanTheirCount() {
        UUID small = tiny("[\"a\"]\n[\"b\"]\n");
        UUID huge = tiny("[\"a\"]\n[\"" + "x".repeat(100_000) + "\"]\n");
        SectionReader reader = new SectionReader(storage, 268435456L);

        reader.table(small, Section.SML, query()).orElseThrow();
        long smallBytes = reader.cachedBytes();
        reader.table(huge, Section.SML, query()).orElseThrow();

        assertTrue(reader.cachedBytes() - smallBytes > 200_000);
    }

    @Test
    void headerOnlyAndZeroColumnTablesStillWeigh() {
        UUID headerOnly = tiny("[\"a\",\"b\"]\n");
        UUID zeroColumns = tiny("[]\n");
        SectionReader reader = new SectionReader(storage, 268435456L);

        reader.table(headerOnly, Section.SML, query()).orElseThrow();
        long afterFirst = reader.cachedBytes();
        reader.table(zeroColumns, Section.SML, query()).orElseThrow();

        assertTrue(afterFirst >= 1);
        assertTrue(reader.cachedBytes() - afterFirst >= 1);
    }

    @Test
    void cachedEntriesAreCappedAtSixteen() {
        SectionReader reader = new SectionReader(storage, 268435456L);

        for (int i = 0; i < 17; i++) {
            reader.table(tiny("[\"a\"]\n"), Section.SML, query()).orElseThrow();
        }

        assertEquals(16, reader.cachedTables());
    }

    /** The estimated weight of the record's SML table, as measured by a fresh reader. */
    private long weigh(ValidationRecord record) {
        SectionReader probe = new SectionReader(storage, Long.MAX_VALUE);
        probe.table(record.id(), Section.SML, query()).orElseThrow();
        return probe.cachedBytes();
    }

    private UUID tiny(String jsonLines) {
        UUID id = UUID.randomUUID();
        storage.write(id, StorageService.SLOT.SECTIONS, Section.SML.fileName(),
            new java.io.ByteArrayInputStream(jsonLines.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return id;
    }

    private static TableQuery query() {
        return new TableQuery(0, 5, null, false, List.of(), List.of());
    }

    private ValidationRecord finished() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            UUID id = sessions.submit("f.mzTab", in, new ValidationOptions(ValidationLevel.INFO, 100, null, null),
                null).id();
            Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
            while (Instant.now().isBefore(deadline)) {
                ValidationRecord record = sessions.find(id).orElseThrow();
                if (record.status() == ValidationStatus.FINISHED || record.status() == ValidationStatus.FAILED) {
                    return record;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("validation did not finish");
        }
    }
}
