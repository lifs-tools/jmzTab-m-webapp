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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.EngineTestSupport;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineResultReader;
import org.lifstools.mztab.validator.webapp.service.validation.MappingFixtures;
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
import org.springframework.test.util.AopTestUtils;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * Validation sessions end to end below the REST layer: submit, the async
 * runner with the real mzTab-M 2.0 engine ({@link EngineTestSupport}),
 * persistence in the storage directory and restart recovery. A 2.1 line is
 * configured whose engine is not running, so submit-time checks can see a 2.1
 * file routed. Semantic validation in the engine asks the local OLS stub of
 * {@link EngineTestSupport}; the webapp's own OLS client, which the
 * in-process endpoints and the CV suggestions still use, is mocked.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class ValidationSessionServiceTest {

    private static final String MZTAB_1_0_TEXT = "This file declares mzTab-version 1.0. mzTab 1.0 is no longer "
        + "supported; the validator checks mzTab-M 2.0 files.";
    private static final String RESTART_TEXT
        = "The validation was interrupted by a server restart. Please validate the file again.";
    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);
    /** The optional rules of the 2.0 default profile that lipidomics-example.mzTab leaves empty, sorted. */
    private static final List<String> EMPTY_OPTIONAL_RULES = List.of("derivatization_agent_may",
        "msrun_fragmentation_method_may", "sample_cell_type_may", "sample_disease_may", "sample_species_may",
        "sample_tissue_may");
    private static final Pattern EMPTY_OPTIONAL_RULE
        = Pattern.compile("accessed by (\\S+) is optional, but was null or empty");

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) throws IOException {
        registry.add("storage.location", () -> storageRoot.toString());
        EngineTestSupport.register(registry);
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        registry.add("engines.mztabm[1].line", () -> "2.1");
        registry.add("engines.mztabm[1].url", () -> "http://localhost:" + closed);
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @MockitoSpyBean
    EngineResultReader resultReader;

    @Autowired
    EngineRegistry registry;

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
        assertNotNull(record.engine());
        assertTrue(record.engine().startsWith("mzTab-M 2.0 · jmzTab-m "), record.engine());
        assertFalse(record.defaultVersionApplied());
        assertNotNull(record.sections());
    }

    @Test
    void jsonUploadIsValidatedLikeTsv() throws Exception {
        ValidationRecord tsv = awaitFinal(submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL, null).id());
        Path json = Files.createTempFile("lipidomics-example", ".json");
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            registry.forLine("2.0").orElseThrow().tsvToJson(in, json);
            ValidationRecord record;
            try (InputStream body = Files.newInputStream(json)) {
                record = awaitFinal(sessions.submit("lipidomics-example.json", body, STRUCTURAL, null).id());
            }

            assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
            assertEquals("2.0.0-M", record.mzTabVersion());
            assertNotNull(record.sections(), "a parsed JSON file has sections");
            assertEquals(tsv.sections(), record.sections());
        } finally {
            Files.deleteIfExists(json);
        }
    }

    @Test
    void fileWithoutVersionIsValidatedByTheDefaultLine() throws Exception {
        String noVersion = resource("/metabolomics/minimal-m-2.0.mztab").replace("MTD\tmzTab-version\t2.0.0-M\n", "");
        ValidationRecord record = awaitFinal(sessions.submit("no-version.mzTab",
            stream(noVersion), STRUCTURAL, null).id());

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        assertTrue(record.defaultVersionApplied());
        assertNull(record.mzTabVersion());
        assertTrue(record.messages().stream().anyMatch(m -> m.getCode() != null && m.getCode().contains("2008")),
            "a missing mzTab-version is still an error: " + record.messages());
    }

    @Test
    void catalogProfileOfOtherLineIsRejectedAtSubmit() throws Exception {
        Set<UUID> before = allSessions();
        ValidationOptions options = new ValidationOptions(ValidationLevel.INFO, 100, "mztab-m-2.0-default", null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> sessions.submit("two-one.mzTab", stream(twoOneFile()), options, null));

        assertEquals("The validation profile mztab-m-2.0-default 1.0.0 applies to mzTab-M 2.0, but the file is "
            + "validated as mzTab-M 2.1.", e.getMessage());
        assertEquals(before, allSessions(), "no session directory may be left behind");
    }

    @Test
    void catalogProfileOfTheFilesLineIsAccepted() throws Exception {
        ValidationOptions options = new ValidationOptions(ValidationLevel.INFO, 100, "mztab-m-2.0-default", null);
        String noVersion = resource("/metabolomics/minimal-m-2.0.mztab").replace("MTD\tmzTab-version\t2.0.0-M\n", "");

        // a file without a version goes to the default line, 2.0
        ValidationRecord record = sessions.submit("no-version.mzTab", stream(noVersion), options, null);

        assertEquals(ValidationStatus.QUEUED, record.status());
        awaitFinal(record.id());
    }

    @Test
    void customMappingIsAcceptedForAnyLine() throws Exception {
        ValidationRecord submitted = sessions.submit("two-one.mzTab", stream(twoOneFile()), STRUCTURAL,
            stream(resource("/validation-profiles/mztab-m-2.0-default/1.0.0.xml")));

        assertEquals(ValidationStatus.QUEUED, submitted.status());
        assertEquals(ProfileRef.custom(), submitted.validationProfile());
        ValidationRecord record = awaitFinal(submitted.id());
        // the 2.1 engine of this test is not running
        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals("The mzTab-M 2.1 validator is temporarily unavailable. Please try again later.",
            record.failureReason());
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
        assertEquals(ValidationMessage.MessageType.ERROR, message.getMessageType());
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
        assertOnlyEmptyOptionalRules(record);
    }

    @Test
    void customMappingIsStoredAndUsed() throws Exception {
        ValidationRecord submitted = submit("/metabolomics/lipidomics-example.mzTab", STRUCTURAL,
            resource("/validation-profiles/mztab-m-2.0-default/1.0.0.xml"));

        assertEquals(new ProfileRef(ProfileRef.Kind.CUSTOM, null, null), submitted.validationProfile());
        assertTrue(storageService.read(submitted.id(), SLOT.MAPPINGFILE, "custom-mapping.xml").isPresent());
        ValidationRecord record = awaitFinal(submitted.id());
        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        // the custom mapping is the default profile, so it must give the same semantic result
        assertOnlyEmptyOptionalRules(record);
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
        UUID id = UUID.randomUUID();
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            storageService.write(id, SLOT.MZTABFILE, "upload.mztab", in);
        }
        writeRecord(record(id, ValidationStatus.QUEUED, Instant.now()));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(resultReader).read(any(), any());
        // the target, not the @Async proxy: both calls run on the test threads and contend for the record
        ValidationRunner target = AopTestUtils.getTargetObject(runner);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = List.of(new Thread(() -> runAfter(start, target, id)),
            new Thread(() -> runAfter(start, target, id)));
        threads.forEach(Thread::start);
        try {
            start.countDown();
            assertTrue(entered.await(30, TimeUnit.SECONDS), "one run must reach the engine");
            // the other run must give up while the first is still reading the engine's result
            Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
            while (threads.stream().allMatch(Thread::isAlive)) {
                assertTrue(Instant.now().isBefore(deadline), "the second run did not return");
                Thread.sleep(10);
            }
        } finally {
            release.countDown();
            for (Thread t : threads) {
                t.join();
            }
        }

        assertEquals(ValidationStatus.FINISHED, sessions.find(id).orElseThrow().status());
        verify(resultReader, times(1)).read(any(), any());
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
        Instant beforeBoot = Instant.now().minus(Duration.ofHours(1));
        writeRecord(record(running, ValidationStatus.RUNNING, beforeBoot));
        writeRecord(record(queued, ValidationStatus.QUEUED, beforeBoot));

        recovery.run(new DefaultApplicationArguments());

        for (UUID id : List.of(running, queued)) {
            ValidationRecord record = sessions.find(id).orElseThrow();
            assertEquals(ValidationStatus.FAILED, record.status());
            assertEquals(RESTART_TEXT, record.failureReason());
            assertNotNull(record.finishedAt());
        }
    }

    @Test
    void recoveryLeavesValidationsOfThisProcessAlone() throws Exception {
        ValidationRecord running = record(UUID.randomUUID(), ValidationStatus.RUNNING, Instant.now());
        ValidationRecord queued = record(UUID.randomUUID(), ValidationStatus.QUEUED, Instant.now());
        writeRecord(running);
        writeRecord(queued);

        recovery.run(new DefaultApplicationArguments());

        assertEquals(running, sessions.find(running.id()).orElseThrow());
        assertEquals(queued, sessions.find(queued.id()).orElseThrow());
        storageService.deleteAll(running.id());
        storageService.deleteAll(queued.id());
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
    void storageCapHoldsForTheUpload() throws Exception {
        byte[] upload = resourceBytes("/metabolomics/lipidomics-example.mzTab");
        Set<UUID> before = allSessions();
        storageProperties.setMaxTotalBytes(storageService.totalBytes() + upload.length / 2);
        try {
            StorageFullException e = assertThrows(StorageFullException.class, () -> sessions.submit(
                "lipidomics-example.mzTab", new ByteArrayInputStream(upload), STRUCTURAL, null));
            assertEquals("The validator's storage is full. Please try again later.", e.getMessage());
            assertEquals(before, allSessions(), "nothing may be left on disk");
        } finally {
            storageProperties.setMaxTotalBytes(10_737_418_240L);
        }
    }

    @Test
    void concurrentSubmitsCannotOvershootTheCap() throws Exception {
        byte[] upload = resourceBytes("/metabolomics/lipidomics-example.mzTab");
        storageProperties.setMaxTotalBytes(storageService.totalBytes() + upload.length + upload.length / 2);
        CountDownLatch start = new CountDownLatch(1);
        List<ValidationRecord> accepted = new CopyOnWriteArrayList<>();
        List<Throwable> refused = new CopyOnWriteArrayList<>();
        Runnable submitOne = () -> {
            try {
                start.await();
                accepted.add(sessions.submit("lipidomics-example.mzTab", new ByteArrayInputStream(upload),
                    STRUCTURAL, null));
            } catch (Throwable t) {
                refused.add(t);
            }
        };
        List<Thread> threads = List.of(new Thread(submitOne), new Thread(submitOne));
        try {
            threads.forEach(Thread::start);
            start.countDown();
            for (Thread t : threads) {
                t.join();
            }
        } finally {
            storageProperties.setMaxTotalBytes(10_737_418_240L);
        }

        assertEquals(1, accepted.size(), "exactly one submit fits under the cap; refused: " + refused);
        assertEquals(1, refused.size());
        assertTrue(refused.get(0) instanceof StorageFullException, refused.get(0).toString());
        awaitFinal(accepted.get(0).id());
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
        ValidationRecord record = record(UUID.randomUUID(), ValidationStatus.QUEUED, Instant.now());
        writeRecord(record);

        ValidationRecord read = sessions.find(record.id()).orElseThrow();

        assertEquals(record, read);
        assertNull(read.outcome());
        sessions.delete(record.id());
    }

    /**
     * Semantic validation of lipidomics-example.mzTab with the 2.0 default
     * profile, as the engine gives it with the answers of the OLS stub: every
     * CV term of the file is found and allowed by its rule, so the only
     * cross-check messages are the optional rules whose element the file
     * leaves empty. A term the engine cannot look up would add an error
     * message for its rule instead.
     */
    private static void assertOnlyEmptyOptionalRules(ValidationRecord record) {
        List<String> crossChecks = record.messages().stream()
            .filter(m -> m.getCategory() == ValidationMessage.Category.CROSS_CHECK)
            .map(ValidationMessage::getMessage).toList();
        assertEquals(EMPTY_OPTIONAL_RULES, crossChecks.stream().map(m -> {
            Matcher rule = EMPTY_OPTIONAL_RULE.matcher(m);
            return rule.find() ? rule.group(1) : m;
        }).sorted().toList(), "cross_check messages: " + crossChecks);
        assertTrue(record.messages().stream().noneMatch(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR),
            "no errors: " + record.messages());
        assertEquals(List.of(), EngineTestSupport.ols().unmatched(), "every OLS request has a stub answer");
    }

    private static void runAfter(CountDownLatch start, ValidationRunner target, UUID id) {
        try {
            start.await();
            target.run(id);
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
        return new String(resourceBytes(name), StandardCharsets.UTF_8);
    }

    private byte[] resourceBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
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

    private static ValidationRecord record(UUID id, ValidationStatus status, Instant now) {
        Map<ValidationLevel, Integer> counts = new EnumMap<>(ValidationLevel.class);
        for (ValidationLevel level : ValidationLevel.values()) {
            counts.put(level, 0);
        }
        return new ValidationRecord(id, "interrupted.mzTab", status, null, ValidationLevel.INFO, 100,
            new ProfileRef(ProfileRef.Kind.NONE, null, null), null, now,
            status == ValidationStatus.RUNNING ? now : null, null, now.plus(7, ChronoUnit.DAYS),
            counts, List.of(), null, null, null, false);
    }

    private String twoOneFile() throws IOException {
        String file = resource("/metabolomics/minimal-m-2.0.mztab");
        assertTrue(file.contains("MTD\tmzTab-version\t2.0.0-M\n"));
        return file.replace("MTD\tmzTab-version\t2.0.0-M\n", "MTD\tmzTab-version\t2.1.0-M\n");
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
