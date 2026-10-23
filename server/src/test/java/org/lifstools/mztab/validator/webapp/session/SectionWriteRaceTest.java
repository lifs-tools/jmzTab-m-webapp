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
package org.lifstools.mztab.validator.webapp.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab.validator.webapp.sections.SectionWriter;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/** A session deleted while its sections are written must not leave files behind. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class SectionWriteRaceTest {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @MockitoSpyBean
    SectionWriter sectionWriter;

    @Autowired
    ValidationRunner runner;

    @Autowired
    ValidationRecordStore records;

    @Autowired
    ValidationSessionService sessions;

    @Autowired
    StorageService storage;

    @Test
    void sessionDeletedWhileSectionsAreWrittenLeavesNothing() throws Exception {
        UUID id = UUID.randomUUID();
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            storage.write(id, SLOT.MZTABFILE, "upload.mztab", in);
        }
        Instant now = Instant.now();
        records.write(ValidationRecord.queued(id, "f.mzTab", ValidationLevel.INFO, 100, ProfileRef.none(), now,
            now.plus(7, ChronoUnit.DAYS)));
        AtomicBoolean deleted = new AtomicBoolean();
        doAnswer(invocation -> {
            deleted.set(sessions.delete(id));
            // the real writer recreates SECTIONS/ of the deleted session
            return invocation.callRealMethod();
        }).when(sectionWriter).write(any(), any());

        // the target, not the @Async proxy: runs on this thread
        ValidationRunner target = AopTestUtils.getTargetObject(runner);
        target.run(id);

        assertTrue(deleted.get(), "the session must have been deleted while running");
        assertTrue(storage.read(id, SLOT.SECTIONS, "sml.jsonl").isEmpty());
        assertFalse(storage.sessions().anyMatch(id::equals));
    }
}
