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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
import org.lifstools.mztab.validator.webapp.service.validation.MappingFixtures;
import org.lifstools.mztab.validator.webapp.service.validation.MzTabMValidator;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.ValidationMessage;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * Validation sessions end to end below the REST layer: submit, the async
 * runner, persistence in the storage directory and restart recovery. OLS is
 * mocked, so the test needs no network.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class ValidationSessionServiceTest {

    private static final String MZTAB_1_0_TEXT = "This file declares mzTab-version 1.0. mzTab 1.0 is no longer "
        + "supported; the validator checks mzTab-M 2.0 files.";
    private static final String RESTART_TEXT
        = "The validation was interrupted by a server restart. Please validate the file again.";
    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @MockitoSpyBean
    CvParameterLookupService lookupService;

    @MockitoSpyBean
    MzTabMValidator validator;

    @Autowired
    ValidationSessionService sessions;

    @Autowired
    ValidationRunner runner;

    @Autowired
    SessionRecovery recovery;

    @Autowired
    StorageService storageService;

    @Autowired
    StorageProperties storageProperties;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ApplicationContext context;

    @Test
    void submitPersistsQueuedRecord() throws Exception {
        ValidationRecord record = submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL, null);

        assertEquals(ValidationStatus.QUEUED, record.status());
        assertEquals("lipidomics-example.mzTab", record.fileName());
        assertEquals(new ProfileRef(ProfileRef.Kind.NONE, null, null), record.validationProfile());
        assertEquals(record.createdAt().plus(7, ChronoUnit.DAYS), record.expiresAt());
        assertEquals(Set.of(ValidationLevel.values()), record.counts().keySet());
        assertTrue(storageService.read(record.id(), SLOT.RECORD, "record.json").isPresent(),
            "RECORD/record.json must exist");
        assertTrue(storageService.read(record.id(), SLOT.MZTABFILE, "upload.mztab").isPresent());
        awaitFinal(record.id());
    }

    @Test
    void validFileFinishes() throws Exception {
        ValidationRecord record = awaitFinal(
            submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL, null).id());

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        assertTrue(Set.of(ValidationOutcome.PASSED, ValidationOutcome.PASSED_WITH_NOTES, ValidationOutcome.FAILED)
            .contains(record.outcome()), "outcome " + record.outcome());
        assertEquals("2.0.0-M", record.mzTabVersion());
        assertEquals(Set.of(ValidationLevel.INFO, ValidationLevel.WARN, ValidationLevel.ERROR),
            record.counts().keySet());
        assertEquals(record.messages().size(), record.counts().values().stream().mapToInt(Integer::intValue).sum());
        assertNotNull(record.startedAt());
        assertNotNull(record.finishedAt());
    }

    @Test
    void legacyFileIsRejectedWithMessage() throws Exception {
        String legacy = "MTD\tmzTab-version\t1.0.0\nMTD\tmzTab-mode\tSummary\nMTD\tmzTab-type\tQuantification\n";
        ValidationRecord record = awaitFinal(sessions.submit("legacy.mzTab",
            new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8)), STRUCTURAL, null).id());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(ValidationOutcome.NOT_PARSED, record.outcome());
        assertEquals(1, record.messages().size());
        ValidationMessage message = record.messages().get(0);
        assertEquals(ValidationMessage.MessageTypeEnum.ERROR, message.getMessageType());
        assertEquals(MZTAB_1_0_TEXT, message.getMessage());
        assertEquals(1, record.counts().get(ValidationLevel.ERROR));
    }

    @Test
    void catalogProfileRunsSemanticValidation() throws Exception {
        ValidationOptions options = new ValidationOptions(ValidationLevel.INFO, 100, "mztab-m-2.0-default", null);
        ValidationRecord submitted = submit("/metabolomics/lipidomics-example.mzTab", options, null);

        assertEquals(new ProfileRef(ProfileRef.Kind.CATALOG, "mztab-m-2.0-default", "1.0.0"),
            submitted.validationProfile());
        ValidationRecord record = awaitFinal(submitted.id());
        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        assertFalse(mockingDetails(lookupService).getInvocations().isEmpty(),
            "semantic validation must use the CV lookup service");
    }

    @Test
    void customMappingIsStoredAndUsed() throws Exception {
        ValidationRecord submitted = submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL,
            resource("/validation-profiles/mztab-m-2.0-default/1.0.0.xml"));

        assertEquals(new ProfileRef(ProfileRef.Kind.CUSTOM, null, null), submitted.validationProfile());
        assertTrue(storageService.read(submitted.id(), SLOT.MAPPINGFILE, "custom-mapping.xml").isPresent());
        ValidationRecord record = awaitFinal(submitted.id());
        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        verify(validator).validateAndParse(any(), any(), anyInt(), any(Path.class));
    }

    @Test
    void customMappingWithDoctypeIsRejected() throws Exception {
        Set<UUID> before = allSessions();

        assertThrows(IllegalArgumentException.class, () -> submit("/metabolomics/lipidomics-example.mzTab",
            STRUCTURAL, MappingFixtures.MAPPING_WITH_DOCTYPE));

        assertEquals(before, allSessions(), "no session directory may be left behind");
    }

    @Test
    void oversizedCustomMappingIsRejected() throws Exception {
        Set<UUID> before = allSessions();
        String huge = MappingFixtures.MINIMAL_MAPPING + " ".repeat(5 * 1024 * 1024);

        assertThrows(IllegalArgumentException.class,
            () -> submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL, huge));

        assertEquals(before, allSessions());
    }

    @Test
    void profileAndCustomMappingTogetherAreRejected() throws Exception {
        Set<UUID> before = allSessions();
        ValidationOptions options = new ValidationOptions(ValidationLevel.INFO, 100, "mztab-m-2.0-default", null);

        assertThrows(IllegalArgumentException.class, () -> submit("/metabolomics/lipidomics-example.mzTab",
            options, MappingFixtures.MINIMAL_MAPPING));

        assertEquals(before, allSessions());
    }

    @Test
    void unknownProfileIsRejected() throws Exception {
        Set<UUID> before = allSessions();
        ValidationOptions options = new ValidationOptions(ValidationLevel.INFO, 100, "nope", null);

        assertThrows(IllegalArgumentException.class,
            () -> submit("/metabolomics/lipidomics-example.mzTab", options, null));

        assertEquals(before, allSessions());
    }

    @Test
    void submitRunsValidationExactlyOnce() throws Exception {
        UUID id = submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL, null).id();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = List.of(new Thread(() -> runAfter(start, id)), new Thread(() -> runAfter(start, id)));
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        ValidationRecord record = awaitFinal(id);
        Thread.sleep(500);

        assertEquals(ValidationStatus.FINISHED, record.status());
        verify(validator, times(1)).validateAndParse(any(), any(), anyInt(), any());
    }

    @Test
    void restartKeepsSessions() throws Exception {
        UUID id = awaitFinal(submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL, null).id()).id();

        context.getBean("init", CommandLineRunner.class).run();

        ValidationRecord record = sessions.find(id).orElseThrow(() -> new AssertionError("session lost on restart"));
        assertEquals(ValidationStatus.FINISHED, record.status());
        assertTrue(storageService.read(id, SLOT.MZTABFILE, "upload.mztab").isPresent());
    }

    @Test
    void interruptedRunIsFailedOnStartup() throws Exception {
        UUID running = UUID.randomUUID();
        UUID queued = UUID.randomUUID();
        writeRecord(record(running, ValidationStatus.RUNNING));
        writeRecord(record(queued, ValidationStatus.QUEUED));

        recovery.run(new DefaultApplicationArguments());

        for (UUID id : List.of(running, queued)) {
            ValidationRecord record = sessions.find(id).orElseThrow();
            assertEquals(ValidationStatus.FAILED, record.status());
            assertEquals(RESTART_TEXT, record.failureReason());
            assertNotNull(record.finishedAt());
        }
    }

    @Test
    void storageFullRefusesSubmit() throws Exception {
        assertEquals(10_737_418_240L, storageProperties.getMaxTotalBytes(), "default storage cap");
        UUID filler = UUID.randomUUID();
        storageService.write(filler, SLOT.MZTABFILE, "upload.mztab", stream("x"));
        Set<UUID> before = allSessions();
        storageProperties.setMaxTotalBytes(1);
        try {
            StorageFullException e = assertThrows(StorageFullException.class,
                () -> submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL, null));
            assertEquals("The validator's storage is full. Please try again later.", e.getMessage());
            assertEquals(before, allSessions());
        } finally {
            storageProperties.setMaxTotalBytes(10_737_418_240L);
            storageService.deleteAll(filler);
        }
    }

    @Test
    void deleteRemovesEverything() throws Exception {
        UUID id = awaitFinal(submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL, null).id()).id();

        assertTrue(sessions.delete(id));
        assertTrue(sessions.find(id).isEmpty());
        assertFalse(allSessions().contains(id));
        assertTrue(storageService.read(id, SLOT.MZTABFILE, "upload.mztab").isEmpty());
        assertFalse(sessions.delete(id));
    }

    @Test
    void unknownIdIsNotFound() {
        assertTrue(sessions.find(UUID.randomUUID()).isEmpty());
        assertFalse(sessions.delete(UUID.randomUUID()));
    }

    @Test
    void recordRoundTripsThroughJson() throws Exception {
        ValidationRecord record = record(UUID.randomUUID(), ValidationStatus.QUEUED);
        writeRecord(record);

        ValidationRecord read = sessions.find(record.id()).orElseThrow();

        assertEquals(record, read);
        assertNull(read.outcome());
        sessions.delete(record.id());
    }

    private void runAfter(CountDownLatch start, UUID id) {
        try {
            start.await();
            runner.run(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ValidationRecord submit(String resource, ValidationOptions options, String mapping) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            String name = resource.substring(resource.lastIndexOf('/') + 1);
            return sessions.submit(name, in, options, mapping == null ? null : stream(mapping));
        }
    }

    private String resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private ValidationRecord awaitFinal(UUID id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            ValidationRecord record = sessions.find(id).orElseThrow();
            if (record.status() == ValidationStatus.FINISHED || record.status() == ValidationStatus.FAILED) {
                return record;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("validation " + id + " did not finish within 60 s");
    }

    private Set<UUID> allSessions() {
        return storageService.sessions().collect(Collectors.toSet());
    }

    private void writeRecord(ValidationRecord record) throws IOException {
        storageService.write(record.id(), SLOT.RECORD, "record.json",
            new ByteArrayInputStream(objectMapper.writeValueAsBytes(record)));
    }

    private static ValidationRecord record(UUID id, ValidationStatus status) {
        Instant now = Instant.now();
        Map<ValidationLevel, Integer> counts = new EnumMap<>(ValidationLevel.class);
        for (ValidationLevel level : ValidationLevel.values()) {
            counts.put(level, 0);
        }
        return new ValidationRecord(id, "interrupted.mzTab", status, null, ValidationLevel.INFO, 100,
            new ProfileRef(ProfileRef.Kind.NONE, null, null), null, now,
            status == ValidationStatus.RUNNING ? now : null, null, now.plus(7, ChronoUnit.DAYS),
            counts, List.of(), null, null);
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
