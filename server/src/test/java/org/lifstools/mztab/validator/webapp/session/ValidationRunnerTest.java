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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.VersionFixtures;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineResultReader;
import org.lifstools.mztab.validator.webapp.engine.EnginesProperties;
import org.lifstools.mztab.validator.webapp.sections.Section;
import org.lifstools.mztab.validator.webapp.sections.SectionReader;
import org.lifstools.mztab.validator.webapp.service.AnalyticsTracker;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.storage.FileSystemStorageService;
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.lifstools.mztab2.server.config.JacksonConfiguration;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.scheduling.TaskScheduler;

/**
 * The runner against stub engines (MockWebServer) on the calling thread:
 * routing by declared version, the engine label, an engine that is down,
 * busy or dies mid-response, and tracking. Retries are scheduled on a mocked
 * {@link TaskScheduler} and run by hand, with a clock the test moves.
 */
class ValidationRunnerTest {

    private static final String UNAVAILABLE_20
        = "The mzTab-M 2.0 validator is temporarily unavailable. Please try again later.";
    private static final String TSV_20 = "MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tx\n";
    private static final String MZTAB_1_0_20 = "This file declares mzTab-version 1.0. mzTab 1.0 is no longer "
        + "supported; the validator checks mzTab-M 2.0 files.";
    private static final String TSV_21 = "MTD\tmzTab-version\t2.1.0-M\nMTD\tmzTab-ID\tx\n";

    @TempDir
    Path root;

    private final List<FakeEngine> engines = new ArrayList<>();
    private FileSystemStorageService storage;
    private ValidationRecordStore records;
    private AnalyticsTracker tracker;
    private TaskScheduler scheduler;
    private MutableClock clock;
    private FakeEngine engine20;
    private FakeEngine engine21;

    @BeforeEach
    void setUp() throws IOException {
        StorageProperties properties = new StorageProperties();
        properties.setLocation(root.toString());
        storage = new FileSystemStorageService(properties);
        storage.init();
        records = new ValidationRecordStore(storage, new JacksonConfiguration().objectMapper());
        tracker = mock(AnalyticsTracker.class);
        scheduler = mock(TaskScheduler.class);
        clock = new MutableClock(Instant.parse("2026-10-30T12:00:00Z"));
        engine20 = engine("2.0", "1.0.8");
        engine21 = engine("2.1", "2.1.0-SNAPSHOT");
    }

    @AfterEach
    void tearDown() throws IOException {
        for (FakeEngine e : engines) {
            e.server.shutdown();
        }
    }

    @Test
    void routesTwoOneFileToTwoOneEngine() throws Exception {
        engine21.answer(ndjson("2.1.0-M"));
        ValidationRunner runner = runner(Duration.ofMinutes(10), null, Map.of("2.0", engine20.url(), "2.1",
            engine21.url()));

        ValidationRecord record = run(runner, TSV_21, ProfileRef.none());

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        assertEquals(1, engine21.validations.size(), "the request must reach the 2.1 engine");
        assertEquals(0, engine20.validations.size());
        assertEquals("2.1.0-M", record.mzTabVersion());
        assertNotNull(record.engine());
        assertTrue(record.engine().contains("2.1"), record.engine());
        assertEquals("mzTab-M 2.1 · jmzTab-m 2.1.0-SNAPSHOT (build abc123)", record.engine());
        assertFalse(record.defaultVersionApplied());
        assertEquals(ValidationOutcome.PASSED, record.outcome());
        assertEquals(new SectionsSummary(1, null, null, null), record.sections());
        assertTrue(storage.read(record.id(), SLOT.SECTIONS, SectionReader.METADATA_FILE).isPresent());
    }

    @Test
    void requestCarriesLevelAndMaxErrors() throws Exception {
        engine20.answer(ndjson("2.0.0-M"));

        ValidationRecord record = run(runner(), TSV_20, ProfileRef.none());

        assertEquals(ValidationStatus.FINISHED, record.status());
        RecordedRequest request = engine20.validations.get(0);
        assertEquals("/engine/v1/validations?level=INFO&maxErrors=100", request.getPath());
        assertFalse(engine20.bodies.get(0).contains("name=\"mapping\""), "structural validation sends no mapping");
    }

    @Test
    void catalogProfileSendsItsMapping() throws Exception {
        engine20.answer(ndjson("2.0.0-M"));

        ValidationRecord record = run(runner(), TSV_20, ProfileRef.catalog("mztab-m-2.0-default", "1.0.0"));

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        String body = engine20.bodies.get(0);
        assertTrue(body.contains("name=\"mapping\""), "the mapping part is missing");
        assertTrue(body.contains("<CvMapping"), "the catalog mapping must be sent");
    }

    @Test
    void fileWithoutVersionRecordsDefaultApplied() throws Exception {
        engine20.answer(ndjson(null));
        ValidationRunner runner = runner(Duration.ofMinutes(10), null, Map.of("2.0", engine20.url(), "2.1",
            engine21.url()));

        ValidationRecord record = run(runner, "MTD\tmzTab-ID\tx\n", ProfileRef.none());

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        assertTrue(record.defaultVersionApplied());
        assertNull(record.mzTabVersion(), "the engine reports no version for such a file");
        assertEquals(1, engine20.validations.size());
        assertEquals(0, engine21.validations.size());
    }

    @Test
    void malformedJsonGoesToTheDefaultLine() throws Exception {
        engine20.answer(ndjson(null));

        ValidationRecord record = run(runner(), "{\"metadata\": {".getBytes(StandardCharsets.UTF_8), ProfileRef.none());

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        assertTrue(record.defaultVersionApplied());
        assertEquals(1, engine20.validations.size(), "the engine's JSON reader reports such a file");
    }

    /** F1: one ISO-8859-1 byte after the version line must not send a 2.1 file to the default (2.0) line. */
    @Test
    void nonUtf8FileRoutesByItsDeclaredVersion() throws Exception {
        engine21.answer(ndjson("2.1.0-M"));
        ValidationRunner runner = runner(Duration.ofMinutes(10), null, Map.of("2.0", engine20.url(), "2.1",
            engine21.url()));

        ValidationRecord record = run(runner, VersionFixtures.LATIN1_21, ProfileRef.none());

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        assertEquals(1, engine21.validations.size(), "the request must reach the 2.1 engine");
        assertEquals(0, engine20.validations.size(), "a 2.1 file must never reach the 2.0 engine");
        assertFalse(record.defaultVersionApplied());
        assertEquals("2.1.0-M", record.mzTabVersion());
    }

    @Test
    void nonUtf8FileOfALineWithoutEngineGetsTheUnsupportedMessage() throws Exception {
        ValidationRecord record = run(runner(), VersionFixtures.LATIN1_21, ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals("This file declares mzTab-version 2.1.0-M. This validator supports mzTab-M 2.0.",
            record.messages().get(0).getMessage());
        assertEquals("2.1.0-M", record.mzTabVersion());
        assertEquals(0, engine20.validations.size(), "a 2.1 file must never reach the 2.0 engine");
    }

    @Test
    void unsupportedLineFinishesWithOneMessage() throws Exception {
        ValidationRunner runner = runner(Duration.ofMinutes(10), null, Map.of("2.0", engine20.url()));

        ValidationRecord record = run(runner, TSV_21, ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(ValidationOutcome.NOT_PARSED, record.outcome());
        assertEquals(1, record.messages().size(), "messages: " + record.messages());
        ValidationMessage message = record.messages().get(0);
        assertEquals(ValidationMessage.MessageType.ERROR, message.getMessageType());
        assertTrue(message.getMessage().startsWith("This file declares mzTab-version 2.1.0-M."), message.getMessage());
        assertEquals("This file declares mzTab-version 2.1.0-M. This validator supports mzTab-M 2.0.",
            message.getMessage());
        assertEquals(message.getMessage(), record.failureReason());
        assertEquals("2.1.0-M", record.mzTabVersion());
        assertEquals(1, record.counts().get(ValidationLevel.ERROR));
        assertEquals(0, engine20.validations.size());
    }

    @Test
    void unsupportedMessageNamesAllLinesAndTheHint() throws Exception {
        ValidationRunner runner = runner(Duration.ofMinutes(10), "https://dev.example.org/validator",
            Map.of("2.0", engine20.url(), "2.1", engine21.url()));

        ValidationRecord record = run(runner, "MTD\tmzTab-version\t2.2.0-M\n", ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals("This file declares mzTab-version 2.2.0-M. This validator supports mzTab-M 2.0 and 2.1."
            + " mzTab-M 2.2 is available for testing on the development validator: "
            + "https://dev.example.org/validator", record.messages().get(0).getMessage());
    }

    @Test
    void legacyFileIsRejectedWithoutAnEngine() throws Exception {
        ValidationRecord record = run(runner(), "MTD\tmzTab-version\t1.0.0\n", ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(ValidationOutcome.NOT_PARSED, record.outcome());
        assertEquals("1.0.0", record.mzTabVersion());
        assertEquals(MZTAB_1_0_20, record.messages().get(0).getMessage());
        assertEquals(0, engine20.validations.size());
        verify(tracker).stopped(record.id(), "validation", "fail");
    }

    /** F4: the legacy message names the lines of this instance. */
    @Test
    void legacyMessageNamesTheConfiguredLines() throws Exception {
        ValidationRunner runner = runner(Duration.ofMinutes(10), null, Map.of("2.0", engine20.url(), "2.1",
            engine21.url()));

        ValidationRecord record = run(runner, "MTD\tmzTab-version\t1.0.0\n", ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals("This file declares mzTab-version 1.0. mzTab 1.0 is no longer supported; the validator checks "
            + "mzTab-M 2.0 and 2.1 files.", record.messages().get(0).getMessage());
        assertEquals(0, engine20.validations.size() + engine21.validations.size());
    }

    @Test
    void legacyJsonIsRejectedWithoutAnEngine() throws Exception {
        ValidationRecord record = run(runner(), "{\"metadata\": {\"mzTab-version\": \"1.0.0\"}}", ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(MZTAB_1_0_20, record.messages().get(0).getMessage());
        assertEquals(0, engine20.validations.size());
    }

    @Test
    void engineDownFinishesFailedNotRunning() throws Exception {
        URI closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = URI.create("http://localhost:" + socket.getLocalPort());
        }
        ValidationRunner runner = runner(Duration.ofMinutes(10), null, Map.of("2.0", closed));

        ValidationRecord record = run(runner, TSV_20, ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(UNAVAILABLE_20, record.failureReason());
        assertTrue(record.failureReason().contains("temporarily unavailable"));
        assertNotNull(record.finishedAt());
        verify(tracker).stopped(record.id(), "validation", "fail");
    }

    @Test
    void truncatedEngineStreamFailsValidationAndRemovesSections() throws Exception {
        String complete = resourceText("/engine-results/lipidomics-example.ndjson");
        List<String> lines = complete.lines().toList();
        // the engine died before its end event
        engine20.answer(ndjsonResponse(String.join("\n", lines.subList(0, lines.size() - 1)) + "\n"));

        ValidationRecord record = run(runner(), TSV_20, ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status(), "never stuck RUNNING");
        assertEquals(UNAVAILABLE_20, record.failureReason());
        assertNoSections(record.id());
        assertTrue(storage.read(record.id(), SLOT.MZTABFILE, ValidationSessionService.UPLOAD).isPresent(),
            "the rest of the session stays");
    }

    @Test
    void engineDisconnectingMidResponseFailsAndRemovesSections() throws Exception {
        String complete = resourceText("/engine-results/lipidomics-example.ndjson");
        engine20.answer(ndjsonResponse(complete).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        ValidationRecord record = run(runner(), TSV_20, ProfileRef.none());

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(UNAVAILABLE_20, record.failureReason());
        assertNoSections(record.id());
    }

    @Test
    void busyEngineRequeuesThenSucceeds() throws Exception {
        engine20.answer(busy(1));
        engine20.answer(ndjson("2.0.0-M"));
        ValidationRunner runner = runner();

        ValidationRecord record = run(runner, TSV_20, ProfileRef.none());

        assertEquals(ValidationStatus.QUEUED, record.status(), "back in the queue while the engine is busy");
        ScheduledRetry retry = lastRetry();
        // max(Retry-After 1 s, backoff 2 s)
        assertEquals(clock.instant().plusSeconds(2), retry.at());
        retry.task().run();

        ValidationRecord done = records.read(record.id()).orElseThrow();
        assertEquals(ValidationStatus.FINISHED, done.status(), "failureReason: " + done.failureReason());
        assertEquals(2, engine20.validations.size());
        verify(tracker, times(1)).started(record.id(), "validation", "init");
        verify(tracker, times(1)).stopped(record.id(), "validation", "success");
        verify(tracker, never()).stopped(record.id(), "validation", "fail");
    }

    @Test
    void busyBackoffDoublesIsCappedAndYieldsToALongerRetryAfter() throws Exception {
        for (int i = 0; i < 6; i++) {
            engine20.answer(busy(0));
        }
        engine20.answer(busy(45));
        engine20.answer(ndjson("2.0.0-M"));
        ValidationRunner runner = runner(Duration.ofHours(1), null, Map.of("2.0", engine20.url()));

        UUID id = run(runner, TSV_20, ProfileRef.none()).id();
        List<Long> delays = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            ScheduledRetry retry = lastRetry(i + 1);
            delays.add(Duration.between(clock.instant(), retry.at()).toSeconds());
            retry.task().run();
        }

        assertEquals(List.of(2L, 4L, 8L, 16L, 30L, 30L, 45L), delays);
        assertEquals(ValidationStatus.FINISHED, records.read(id).orElseThrow().status());
    }

    @Test
    void busyBeyondJobTimeoutFails() throws Exception {
        for (int i = 0; i < 5; i++) {
            engine20.answer(busy(1));
        }
        ValidationRunner runner = runner(Duration.ofSeconds(3), null, Map.of("2.0", engine20.url()));

        UUID id = run(runner, TSV_20, ProfileRef.none()).id();
        assertEquals(ValidationStatus.QUEUED, records.read(id).orElseThrow().status());
        clock.advance(Duration.ofSeconds(2));
        lastRetry(1).task().run();
        assertEquals(ValidationStatus.QUEUED, records.read(id).orElseThrow().status());
        clock.advance(Duration.ofSeconds(4));
        lastRetry(2).task().run();

        ValidationRecord record = records.read(id).orElseThrow();
        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(UNAVAILABLE_20, record.failureReason());
        assertEquals(3, engine20.validations.size());
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        verify(tracker, times(1)).stopped(id, "validation", "fail");
    }

    @Test
    void sessionDeletedWhileQueuedForARetryIsNotRetried() throws Exception {
        engine20.answer(busy(1));
        ValidationRunner runner = runner();
        UUID id = run(runner, TSV_20, ProfileRef.none()).id();
        ScheduledRetry retry = lastRetry();

        assertTrue(records.delete(id));
        retry.task().run();

        assertEquals(1, engine20.validations.size());
        assertTrue(records.read(id).isEmpty());
    }

    @Test
    void engineVersionDifferingFromDetectedIsKeptAndLogged() throws Exception {
        engine20.answer(ndjson("2.0.1-M"));
        Logger logger = (Logger) LoggerFactory.getLogger(ValidationRunner.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            ValidationRecord record = run(runner(), TSV_20, ProfileRef.none());

            assertEquals("2.0.1-M", record.mzTabVersion());
            assertTrue(logged.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("2.0.1-M") && m.contains("2.0.0-M")), "logged: " + logged.list);
        } finally {
            logger.detachAppender(logged);
        }
    }

    /** Spec §5.4.3: the engine's reading of the version is kept; one of another line than the routed is a warning. */
    @Test
    void engineReportingAnotherLineIsAWarning() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(ValidationRunner.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            engine20.answer(ndjson("2.1.0-M"));
            ValidationRecord declared = run(runner(), TSV_20, ProfileRef.none());
            engine20.answer(ndjson("2.1.0-M"));
            ValidationRecord undeclared = run(runner(), "MTD\tmzTab-ID\tx\n", ProfileRef.none());

            assertEquals("2.1.0-M", declared.mzTabVersion());
            assertEquals("2.1.0-M", undeclared.mzTabVersion());
            List<ILoggingEvent> warnings = logged.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("2.1.0-M")).toList();
            assertEquals(2, warnings.size(), "logged: " + logged.list);
            assertTrue(warnings.get(0).getFormattedMessage().contains(declared.id().toString())
                && warnings.get(0).getFormattedMessage().contains("2.0.0-M"), warnings.get(0).getFormattedMessage());
            assertTrue(warnings.get(1).getFormattedMessage().contains(undeclared.id().toString())
                && warnings.get(1).getFormattedMessage().contains("declares none"),
                warnings.get(1).getFormattedMessage());
        } finally {
            logger.detachAppender(logged);
        }
    }

    @Test
    void engineVersionOfTheRoutedLineIsNoWarning() throws Exception {
        engine20.answer(ndjson("2.0.0-M"));
        Logger logger = (Logger) LoggerFactory.getLogger(ValidationRunner.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try {
            run(runner(), "MTD\tmzTab-ID\tx\n", ProfileRef.none());

            assertTrue(logged.list.stream().noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN),
                "logged: " + logged.list);
        } finally {
            logger.detachAppender(logged);
        }
    }

    @Test
    void outcomeFollowsTheMessages() throws Exception {
        engine20.answer(ndjsonResponse("""
            {"type":"outcome","parsed":true,"mzTabVersion":"2.0.0-M","messages":[{"code":"","category":"logical","message_type":"warn","message":"w","line_number":3}]}
            {"type":"end","sections":{"metadataLines":0}}
            """));
        engine20.answer(ndjsonResponse("""
            {"type":"outcome","parsed":true,"mzTabVersion":"2.0.0-M","messages":[{"code":"","category":"format","message_type":"error","message":"e","line_number":3}]}
            {"type":"end","sections":{"metadataLines":0}}
            """));
        engine20.answer(ndjsonResponse("""
            {"type":"outcome","parsed":false,"mzTabVersion":null,"messages":[{"code":"","category":"format","message_type":"error","message":"e","line_number":-1}]}
            {"type":"end","sections":null}
            """));
        ValidationRunner runner = runner();

        List<ValidationOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            outcomes.add(run(runner, TSV_20, ProfileRef.none()).outcome());
        }

        assertEquals(List.of(ValidationOutcome.PASSED_WITH_NOTES, ValidationOutcome.FAILED,
            ValidationOutcome.NOT_PARSED), outcomes);
    }

    @Test
    void successfulRunIsTracked() throws Exception {
        engine20.answer(ndjson("2.0.0-M"));

        ValidationRecord record = run(runner(), TSV_20, ProfileRef.none());

        assertEquals(ValidationStatus.FINISHED, record.status());
        verify(tracker).started(record.id(), "validation", "init");
        verify(tracker).stopped(record.id(), "validation", "success");
        verify(tracker, never()).stopped(record.id(), "validation", "fail");
    }

    @Test
    void failedRunIsTracked() throws Exception {
        UUID id = UUID.randomUUID();
        // no upload: the run fails unexpectedly
        records.write(queued(id, ProfileRef.none()));

        runner().run(id);

        ValidationRecord record = records.read(id).orElseThrow();
        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(ValidationRunner.UNEXPECTED_ERROR, record.failureReason());
        verify(tracker).started(id, "validation", "init");
        verify(tracker).stopped(id, "validation", "fail");
        verify(tracker, never()).stopped(id, "validation", "success");
        verifyNoInteractions(scheduler);
    }

    @Test
    void failingTrackerDoesNotFailTheValidation() throws Exception {
        engine20.answer(ndjson("2.0.0-M"));
        doThrow(new IllegalStateException("tracker down")).when(tracker).started(any(), anyString(), anyString());
        doThrow(new IllegalStateException("tracker down")).when(tracker).stopped(any(), anyString(), anyString());

        ValidationRecord record = run(runner(), TSV_20, ProfileRef.none());

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
    }

    @Test
    void runsOnlyQueuedValidations() throws Exception {
        engine20.answer(ndjson("2.0.0-M"));
        ValidationRunner runner = runner();
        ValidationRecord first = run(runner, TSV_20, ProfileRef.none());

        runner.run(first.id());

        assertEquals(1, engine20.validations.size(), "a finished validation does not run again");
    }

    // ---- harness

    private ValidationRunner runner() {
        return runner(Duration.ofMinutes(10), null, Map.of("2.0", engine20.url()));
    }

    private ValidationRunner runner(Duration jobTimeout, String hintUrl, Map<String, URI> lines) {
        List<EnginesProperties.Engine> configured = new ArrayList<>();
        // keep 2.0 first: the registry lists lines in configuration order
        new TreeMap<>(lines).forEach((line, url) -> configured.add(new EnginesProperties.Engine(line, url)));
        EnginesProperties properties = new EnginesProperties("2.0", configured, Duration.ofSeconds(2), jobTimeout,
            hintUrl);
        ValidationProfileCatalog catalog = new ValidationProfileCatalog(new ObjectMapper(), new DefaultResourceLoader(),
            "classpath:/validation-profiles/index.json");
        return new ValidationRunner(records, storage, catalog, new EngineRegistry(properties),
            new EngineResultReader(storage), properties, tracker, scheduler, Runnable::run, clock);
    }

    private ValidationRecord run(ValidationRunner runner, String upload, ProfileRef profile) {
        return run(runner, upload.getBytes(StandardCharsets.UTF_8), profile);
    }

    private ValidationRecord run(ValidationRunner runner, byte[] upload, ProfileRef profile) {
        UUID id = UUID.randomUUID();
        storage.write(id, SLOT.MZTABFILE, ValidationSessionService.UPLOAD, new ByteArrayInputStream(upload));
        records.write(queued(id, profile));
        runner.run(id);
        return records.read(id).orElseThrow();
    }

    private ValidationRecord queued(UUID id, ProfileRef profile) {
        Instant now = clock.instant();
        return ValidationRecord.queued(id, "f.mzTab", ValidationLevel.INFO, 100, profile, now,
            now.plus(7, ChronoUnit.DAYS));
    }

    private record ScheduledRetry(Runnable task, Instant at) {

    }

    private ScheduledRetry lastRetry() {
        return lastRetry(1);
    }

    /** The {@code count}-th retry scheduled so far. */
    private ScheduledRetry lastRetry(int count) {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler, times(count)).schedule(task.capture(), at.capture());
        return new ScheduledRetry(task.getValue(), at.getValue());
    }

    private void assertNoSections(UUID id) {
        for (String name : List.of(SectionReader.METADATA_FILE, Section.SML.fileName(), Section.SMF.fileName(),
            Section.SME.fileName())) {
            assertTrue(storage.read(id, SLOT.SECTIONS, name).isEmpty(), name + " must not be left behind");
        }
    }

    private static MockResponse ndjson(String version) {
        String v = version == null ? "null" : "\"" + version + "\"";
        String metadata = version == null ? ""
            : "{\"type\":\"metadata\",\"line\":1,\"key\":\"mzTab-version\",\"value\":" + v + "}\n";
        int lines = version == null ? 0 : 1;
        return ndjsonResponse("{\"type\":\"outcome\",\"parsed\":true,\"mzTabVersion\":" + v + ",\"messages\":[]}\n"
            + metadata + "{\"type\":\"end\",\"sections\":{\"metadataLines\":" + lines + "}}\n");
    }

    private static MockResponse ndjsonResponse(String body) {
        return new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/x-ndjson")
            .setBody(body);
    }

    private static MockResponse busy(int retryAfter) {
        return new MockResponse().setResponseCode(503).setHeader("Retry-After", Integer.toString(retryAfter))
            .setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"busy\",\"message\":\"The engine is busy.\"}");
    }

    private String resourceText(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private FakeEngine engine(String line, String jmztabm) throws IOException {
        FakeEngine engine = new FakeEngine(line, jmztabm);
        engines.add(engine);
        return engine;
    }

    /** An engine stub: its info, and the queued answers to validation requests. */
    private static final class FakeEngine {

        final MockWebServer server = new MockWebServer();
        final List<RecordedRequest> validations = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        private final ConcurrentLinkedDeque<MockResponse> answers = new ConcurrentLinkedDeque<>();

        FakeEngine(String line, String jmztabm) throws IOException {
            String info = "{\"line\":\"" + line + "\",\"mzTabVersions\":[\"" + line + ".0-M\"],\"jmztabmVersion\":\""
                + jmztabm + "\",\"build\":\"abc123\",\"maxUploadBytes\":67108864,\"maxConcurrentJobs\":2}";
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String path = request.getPath() == null ? "" : request.getPath();
                    if (path.startsWith("/engine/v1/info")) {
                        return new MockResponse().setHeader("Content-Type", "application/json").setBody(info);
                    }
                    if (path.startsWith("/engine/v1/validations")) {
                        validations.add(request);
                        bodies.add(request.getBody().readUtf8());
                        MockResponse next = answers.poll();
                        return next != null ? next : new MockResponse().setResponseCode(500);
                    }
                    return new MockResponse().setResponseCode(404);
                }
            });
            server.start();
        }

        void answer(MockResponse response) {
            answers.add(response);
        }

        URI url() {
            return URI.create("http://localhost:" + server.getPort());
        }
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
