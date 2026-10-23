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
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
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

/** The table cache is bounded by the number of cached cells. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class SectionCacheTest {

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
    StorageService storage;

    @Test
    void newReaderStartsEmpty() {
        SectionReader reader = new SectionReader(storage, 4_000_000L);
        assertEquals(0, reader.cachedTables());
    }

    @Test
    void evictsLeastRecentlyUsedTablesToFitTheBudget() throws Exception {
        ValidationRecord first = finished();
        ValidationRecord second = finished();
        long cells = (long) (first.sections().sml().rows() + 1) * first.sections().sml().columns();
        SectionReader reader = new SectionReader(storage, cells + cells / 2);

        reader.table(first.id(), Section.SML, query()).orElseThrow();
        assertEquals(1, reader.cachedTables());
        assertEquals(cells, reader.cachedCells());

        reader.table(second.id(), Section.SML, query()).orElseThrow();
        assertEquals(1, reader.cachedTables(), "the first table must have been evicted");
        assertEquals(cells, reader.cachedCells());
        // the evicted table is still served (reloaded)
        assertEquals(first.sections().sml().rows(),
            reader.table(first.id(), Section.SML, query()).orElseThrow().totalRows());
    }

    @Test
    void keepsTablesWhileTheyFit() throws Exception {
        ValidationRecord first = finished();
        ValidationRecord second = finished();
        long cells = (long) (first.sections().sml().rows() + 1) * first.sections().sml().columns();
        SectionReader reader = new SectionReader(storage, cells * 2);

        reader.table(first.id(), Section.SML, query()).orElseThrow();
        reader.table(second.id(), Section.SML, query()).orElseThrow();

        assertEquals(2, reader.cachedTables());
        assertEquals(2 * cells, reader.cachedCells());
    }

    @Test
    void tableOverTheBudgetIsServedButNotCached() throws Exception {
        ValidationRecord record = finished();
        long cells = (long) (record.sections().sml().rows() + 1) * record.sections().sml().columns();
        SectionReader reader = new SectionReader(storage, cells - 1);

        TablePage page = reader.table(record.id(), Section.SML, query()).orElseThrow();

        assertEquals(record.sections().sml().rows(), page.totalRows());
        assertTrue(page.rows().size() > 0);
        assertEquals(0, reader.cachedTables());
        assertEquals(0, reader.cachedCells());
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
