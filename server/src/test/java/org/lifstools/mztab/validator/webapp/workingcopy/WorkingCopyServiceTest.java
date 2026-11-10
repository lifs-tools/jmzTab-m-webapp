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
package org.lifstools.mztab.validator.webapp.workingcopy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.lifstools.mztab.validator.webapp.EngineTestSupport;
import org.lifstools.mztab.validator.webapp.engine.EngineMessages;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.guidance.GuidanceCatalog;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.lifstools.mztab.validator.webapp.session.StorageFullException;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.session.ValidationStatus;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.DeleteLine;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.InsertLine;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.SetMetadataValue;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Working copies below the REST layer, validated by the real mzTab-M 2.0
 * engine ({@link org.lifstools.mztab.validator.webapp.EngineTestSupport})
 * behind an {@link EngineProxy}, which can hold a validation request to make
 * the overlap of a run and an edit deterministic. The 2.1 engine, if it was
 * built, is behind a proxy of its own, for the tests that run on both lines.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ExtendWith(AwaitAsyncValidations.class)
class WorkingCopyServiceTest {

    static final EngineProxy ENGINE = EngineProxy.start();
    static final EngineProxy ENGINE_21 = EngineProxy.start("2.1");
    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DETECTOR = "[MS, MS:1000624, inductive detector, ]";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        ENGINE.register(registry);
        if (EngineTestSupport.has21()) {
            ENGINE_21.registerAt(registry, 1);
        }
    }

    @Autowired
    WorkingCopyService copies;

    @Autowired
    WorkingCopyReader reader;

    @Autowired
    ValidationSessionService sessions;

    @Autowired
    EngineRegistry registry;

    @Autowired
    StorageProperties storageProperties;

    @Autowired
    ValidationProfileCatalog catalog;

    @Autowired
    StorageService storage;

    @Autowired
    ObjectMapper json;

    @AfterEach
    void forward() {
        ENGINE.reset();
        ENGINE_21.reset();
    }

    static String example() throws IOException {
        try (InputStream in = WorkingCopyServiceTest.class
            .getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The example with an empty detector parameter on line 24 (Error-1012). */
    static String emptyDetector() throws IOException {
        String text = example();
        assertTrue(text.contains(DETECTOR));
        return text.replace(DETECTOR, "[, , , ]");
    }

    /** Submits a validation and waits until it has ended. */
    private UUID submit(String name, byte[] content) throws InterruptedException {
        UUID id = sessions.submit(name, new ByteArrayInputStream(content), STRUCTURAL, null).id();
        Instant end = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(end)) {
            ValidationStatus s = sessions.find(id).orElseThrow().status();
            if (s == ValidationStatus.FINISHED || s == ValidationStatus.FAILED) {
                return id;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("validation " + id + " did not end");
    }

    private UUID submit(String name, String content) throws InterruptedException {
        return submit(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Polls until the copy's last run has ended on {@code revision}. */
    private WorkingCopyValidation awaitRun(UUID id, long revision) throws InterruptedException {
        Instant end = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(end)) {
            WorkingCopyValidation v = copies.info(id).validation();
            if (v != null && v.status() != Status.RUNNING && v.revision() == revision) {
                return v;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no ended run on revision " + revision + ": " + copies.info(id).validation());
    }

    private static Predicate<ValidationMessage> code(String typeCode) {
        return m -> GuidanceCatalog.typeCodeOf(m).equals(typeCode);
    }

    private static EditBatch batch(long revision, EditOperation... ops) {
        return new EditBatch(revision, List.of(ops));
    }

    @Test
    void createFromValidationIsIdempotent() throws Exception {
        UUID id = submit("idem.mzTab", example());

        WorkingCopyService.Creation first = copies.createOrFind(id, SourceEncoding.UTF_8);
        assertTrue(first.created());
        WorkingCopyInfo info = first.info();
        assertEquals(id, info.id());
        assertEquals("idem.mzTab", info.fileName());
        assertEquals(0, info.revision());
        assertEquals(example().lines().count(), info.lineCount());
        assertFalse(info.canUndo());
        assertFalse(info.canRedo());
        assertEquals("2.0.0-M", info.mzTabVersion());
        assertNotNull(info.sections());
        assertTrue(info.sections().metadataLines() > 0);
        assertNotNull(info.sections().sml());
        assertNull(info.validation());
        assertTrue(info.expiresAt().isAfter(Instant.now().plus(6, ChronoUnit.DAYS)));

        copies.edit(id, batch(0, new SetMetadataValue(3, "changed")));
        WorkingCopyService.Creation again = copies.createOrFind(id, SourceEncoding.UTF_8);
        assertFalse(again.created());
        assertEquals(1, again.info().revision());
        assertTrue(again.info().canUndo());
        assertEquals("changed", reader.lines(id, 3, 1).get(0).cells().get(2));
        assertEquals(1, copies.create(id, SourceEncoding.WINDOWS_1252).revision(),
            "an existing copy is returned unchanged, whatever the encoding");
    }

    @Test
    void createRefusesLatin1WithLine() throws Exception {
        String text = example();
        assertTrue(text.lines().skip(2).findFirst().orElseThrow().contains("ISAS-2018-1234"));
        UUID id = submit("latin1.mzTab", text.replace("ISAS-2018-1234", "ISÄS-2018-1234")
            .getBytes(StandardCharsets.ISO_8859_1));

        NotUtf8Exception e = assertThrows(NotUtf8Exception.class, () -> copies.create(id, SourceEncoding.UTF_8));
        assertEquals(3, e.lineNumber());
        assertThrows(NoSuchWorkingCopyException.class, () -> copies.info(id), "nothing is kept");

        WorkingCopyInfo info = copies.create(id, SourceEncoding.WINDOWS_1252);
        assertEquals(0, info.revision());
        assertEquals("ISÄS-2018-1234", reader.lines(id, 3, 1).get(0).cells().get(2));
    }

    @Test
    void jsonUploadBecomesTsv() throws Exception {
        Path json = Files.createTempFile("lipidomics-example", ".json");
        try {
            try (InputStream in = getClass().getResourceAsStream("/metabolomics/lipidomics-example.mzTab")) {
                registry.forLine("2.0").orElseThrow().tsvToJson(in, json);
            }
            UUID id = submit("example.json", Files.readAllBytes(json));

            WorkingCopyInfo info = copies.create(id, SourceEncoding.UTF_8);

            assertEquals("example.json", info.fileName());
            assertTrue(info.lineCount() > 10);
            Line first = reader.lines(id, 1, 1).get(0);
            assertTrue(first.text().startsWith("MTD\tmzTab-version"), first.text());
            ByteArrayOutputStream original = new ByteArrayOutputStream();
            copies.writeOriginal(id, original);
            assertTrue(original.toString(StandardCharsets.UTF_8).strip().startsWith("{"),
                "the original is the JSON as uploaded");
        } finally {
            Files.deleteIfExists(json);
        }
    }

    @Test
    void editThenValidateShowsFixed() throws Exception {
        UUID id = submit("fixed.mzTab", emptyDetector());
        ValidationRecord record = sessions.find(id).orElseThrow();
        assertTrue(record.messages().stream().anyMatch(code("Error-1012")), "the session reports the empty detector");
        copies.create(id, SourceEncoding.UTF_8);
        assertEquals("instrument[1]-detector", reader.lines(id, 24, 1).get(0).cells().get(1));

        WorkingCopyInfo edited = copies.edit(id, batch(0, new DeleteLine(24)));
        assertEquals(1, edited.revision());
        WorkingCopyInfo requested = copies.requestValidation(id, false);
        assertNotNull(requested.validation());

        WorkingCopyValidation v = awaitRun(id, 1);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertTrue(v.parsed());
        assertFalse(v.stale());
        assertFalse(v.semantic());
        assertNull(v.readUpTo());
        assertTrue(v.fixed().stream().anyMatch(code("Error-1012")), "fixed: " + v.fixed());
        assertTrue(v.messages().stream().map(TrackedMessage::message).noneMatch(code("Error-1012")));
    }

    @Test
    void abortedParseReportsReadUpTo() throws Exception {
        List<String> lines = new java.util.ArrayList<>(example().lines().toList());
        lines.set(39, "XYZ\tfoo");
        UUID id = submit("aborted.mzTab", String.join("\n", lines) + "\n");
        WorkingCopyInfo info = copies.create(id, SourceEncoding.UTF_8);
        assertEquals("XYZ\tfoo", reader.lines(id, 40, 1).get(0).text());

        copies.requestValidation(id, false);
        WorkingCopyValidation v = awaitRun(id, 0);

        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertFalse(v.parsed());
        assertEquals(40L, v.readUpTo());
        assertTrue(v.guidance().stream().anyMatch(g -> g.stopsParsing() && g.lines().stream()
            .anyMatch(q -> q.lineNumber() == 40 && q.text().equals("XYZ\tfoo"))), "guidance: " + v.guidance());
        assertEquals(10, reader.lines(id, 41, 10).size(), "the lines after the stop can still be read");
        assertTrue(info.lineCount() >= 50);
    }

    /**
     * While a run is RUNNING, the messages shown are those of the run before: their line numbers are of that run's
     * revision, which is what an action started from a message must name (never the running run's).
     */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void runningInfoKeepsTheMessagesRevision(String line) throws Exception {
        assumeLine(line);
        UUID id = submit("running-" + line + ".mzTab", exampleOf(line));
        copies.create(id, SourceEncoding.UTF_8);
        copies.edit(id, batch(0, new SetMetadataValue(3, "revision-one")));
        copies.requestValidation(id, false);
        WorkingCopyValidation first = awaitRun(id, 1);
        assertEquals(Status.DONE, first.status(), "failure: " + first.failureReason());
        assertEquals(1L, first.messagesRevision(), "an ended run's messages are of its own revision");

        copies.edit(id, batch(1, new SetMetadataValue(3, "revision-two")));
        EngineProxy.Gate gate = proxyOf(line).holdNextValidation();
        copies.requestValidation(id, false);
        assertTrue(gate.awaitArrival(TIMEOUT), "the second run reached the engine");

        WorkingCopyValidation during = copies.info(id).validation();
        assertEquals(Status.RUNNING, during.status());
        assertEquals(2L, during.revision());
        assertFalse(during.stale(), "the running run is of the current text");
        assertEquals(first.messages(), during.messages(), "the messages of the run before stay shown");
        assertEquals(1L, during.messagesRevision(), "and they are of that run's revision, not of the running one");

        gate.release();
        WorkingCopyValidation second = awaitRun(id, 2);
        assertEquals(2L, second.messagesRevision());
    }

    @Test
    void supersededRunIsMarkedStale() throws Exception {
        UUID id = submit("stale.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        copies.edit(id, batch(0, new SetMetadataValue(3, "revision-one")));
        int jobsBefore = ENGINE.jobs().size();

        EngineProxy.Gate first = ENGINE.holdNextValidation();
        copies.requestValidation(id, false);
        assertTrue(first.awaitArrival(TIMEOUT), "the first run reached the engine");
        copies.edit(id, batch(1, new SetMetadataValue(3, "revision-two")));

        WorkingCopyValidation during = copies.info(id).validation();
        assertNotNull(during);
        assertEquals(Status.RUNNING, during.status());
        assertEquals(1, during.revision());
        assertTrue(during.stale(), "the text changed under the running validation");

        EngineProxy.Gate second = ENGINE.holdNextValidation();
        first.release();
        assertTrue(second.awaitArrival(TIMEOUT), "a second run follows, without another request");
        WorkingCopyValidation between = copies.info(id).validation();
        assertEquals(Status.RUNNING, between.status());
        assertEquals(2, between.revision());
        assertFalse(between.stale());
        second.release();

        WorkingCopyValidation v = awaitRun(id, 2);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertFalse(v.stale());
        List<EngineProxy.Recorded> validations = ENGINE.jobs().subList(jobsBefore, ENGINE.jobs().size()).stream()
            .filter(j -> j.path().startsWith("/engine/v1/validations")).toList();
        assertEquals(2, validations.size(), "one run per revision");
        assertTrue(new String(validations.get(0).part("file").orElseThrow(), StandardCharsets.UTF_8)
            .contains("revision-one"));
        assertTrue(new String(validations.get(1).part("file").orElseThrow(), StandardCharsets.UTF_8)
            .contains("revision-two"));
    }

    /**
     * An edit that commits after a run stored its result but before the run
     * decides whether to run again, with the edit's own bookkeeping not yet
     * done, still gets a run of its own.
     */
    @Test
    void anEditJustBeforeARunEndsIsValidatedToo() throws Exception {
        UUID id = submit("late-edit.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        AtomicBoolean once = new AtomicBoolean();
        copies.beforeRunEnds(copy -> {
            if (copy.equals(id) && once.compareAndSet(false, true)) {
                // the edit as committed by edit(), without what edit() does after the commit
                new WorkingCopyEditor().apply(copies.db(id), batch(0, new SetMetadataValue(3, "late")));
            }
        });
        try {
            copies.requestValidation(id, false);
            WorkingCopyValidation v = awaitRun(id, 1);
            assertTrue(once.get());
            assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
            assertFalse(v.stale());
        } finally {
            copies.beforeRunEnds(null);
        }
    }

    @Test
    void aRequestDuringARunIsCoalesced() throws Exception {
        UUID id = submit("coalesce.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        int jobsBefore = ENGINE.jobs().size();

        EngineProxy.Gate first = ENGINE.holdNextValidation();
        copies.requestValidation(id, false);
        assertTrue(first.awaitArrival(TIMEOUT));
        copies.requestValidation(id, false);
        copies.requestValidation(id, false);
        first.release();

        assertEquals(Status.DONE, awaitRun(id, 0).status());
        long validations = ENGINE.jobs().subList(jobsBefore, ENGINE.jobs().size()).stream()
            .filter(j -> j.path().startsWith("/engine/v1/validations")).count();
        assertEquals(1, validations, "the same text and kind is not validated twice");
    }

    @Test
    void versionFollowsTheText() throws Exception {
        String text = example();
        String versionLine = text.lines().skip(1).findFirst().orElseThrow();
        assertTrue(versionLine.startsWith("MTD\tmzTab-version\t2.0.0-M"));
        UUID id = submit("no-version.mzTab", text.replace(versionLine + "\n", ""));
        WorkingCopyInfo created = copies.create(id, SourceEncoding.UTF_8);
        assertFalse("2.0.0-M".equals(created.mzTabVersion()), "the file declares no version");

        copies.edit(id, batch(0, new InsertLine(1, "MTD\tmzTab-version\t2.0.0-M")));
        copies.requestValidation(id, false);
        WorkingCopyValidation v = awaitRun(id, 1);

        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertEquals("2.0.0-M", copies.info(id).mzTabVersion());
    }

    @Test
    void jsonDownloadNeedsAValidCurrentState() throws Exception {
        UUID id = submit("json.mzTab", emptyDetector());
        copies.create(id, SourceEncoding.UTF_8);
        assertThrows(IllegalStateException.class, () -> copies.writeJson(id, new ByteArrayOutputStream()),
            "not validated yet");

        copies.requestValidation(id, false);
        WorkingCopyValidation withError = awaitRun(id, 0);
        assertTrue(withError.messages().stream().map(TrackedMessage::message).anyMatch(code("Error-1012")));
        assertThrows(IllegalStateException.class, () -> copies.writeJson(id, new ByteArrayOutputStream()),
            "the current state has errors");

        copies.edit(id, batch(0, new SetMetadataValue(24, DETECTOR)));
        assertThrows(IllegalStateException.class, () -> copies.writeJson(id, new ByteArrayOutputStream()),
            "the last validation is of an older revision");
        copies.requestValidation(id, false);
        WorkingCopyValidation fixed = awaitRun(id, 1);
        assertTrue(fixed.messages().stream().map(TrackedMessage::message)
            .noneMatch(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR), "messages: " + fixed);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copies.writeJson(id, out);
        String json = out.toString(StandardCharsets.UTF_8);
        assertTrue(json.strip().startsWith("{"), json.substring(0, Math.min(200, json.length())));
        assertTrue(json.contains("2.0.0-M"));
    }

    @Test
    void deletingTheSessionDeletesTheCopy() throws Exception {
        UUID id = submit("delete.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        Path slot = storageRoot.resolve(id.toString()).resolve("WORKINGCOPY");
        assertTrue(Files.isDirectory(slot));

        assertTrue(sessions.delete(id));

        assertFalse(Files.exists(slot));
        assertThrows(NoSuchWorkingCopyException.class, () -> copies.info(id));
        assertThrows(NoSuchWorkingCopyException.class, () -> reader.lines(id, 1, 1));
        assertThrows(NoSuchWorkingCopyException.class, () -> copies.create(id, SourceEncoding.UTF_8),
            "no session, no copy");
    }

    @Test
    void aDatabaseErrorAfterTheSessionWasDeletedIsNoSuchCopy() throws Exception {
        UUID id = submit("delete-during.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        WorkingCopyDb db = copies.db(id);
        assertTrue(sessions.delete(id));

        assertThrows(DataAccessException.class, db::lineCount, "a request in flight hits the database error");
        assertThrows(NoSuchWorkingCopyException.class, () -> copies.guard(id, db::lineCount));
    }

    @Test
    void duplicateColumnsCountTheRowsWhoseValuesDiffer() throws Exception {
        StringBuilder text = new StringBuilder("MTD\tmzTab-version\t2.0.0-M\n"
            + "SMH\tSML_ID\tn\tx\tn\tx\tn\t\t\n"
            // agree; one value only; null and empty hold no value
            + "SML\t1\ta\t1\ta\t1\ta\n"
            + "SML\t2\ta\t1\tnull\t1\t\n"
            + "SML\t3\tnull\t1\t\n");
        for (int i = 0; i < 25; i++) {
            // two of three differ, in the n columns only
            text.append("SML\t").append(10 + i).append("\ta\t1\tb\t1\n");
        }
        UUID id = submit("duplicates.mzTab", text.toString());
        copies.create(id, SourceEncoding.UTF_8);

        List<DuplicateColumn> found = reader.duplicates(id, "SMH");

        assertEquals(List.of("n", "x"), found.stream().map(DuplicateColumn::name).toList());
        DuplicateColumn n = found.get(0);
        assertEquals(List.of(2, 4, 6), n.positions());
        assertEquals(25, n.conflictingRows());
        assertEquals(WorkingCopyReader.MAX_DUPLICATE_SAMPLES, n.samples().size());
        assertEquals(new DuplicateColumn.Sample(6, List.of("a", "b", "")), n.samples().get(0));
        DuplicateColumn x = found.get(1);
        assertEquals(List.of(3, 5), x.positions());
        assertEquals(0, x.conflictingRows());
        assertTrue(x.samples().isEmpty());
        assertTrue(reader.duplicates(id, "SFH").isEmpty(), "no such header");
        assertThrows(IllegalArgumentException.class, () -> reader.duplicates(id, "SML"));
        assertThrows(IllegalArgumentException.class, () -> reader.duplicates(id, null));
    }

    @Test
    void deleteRemovesOnlyTheCopy() throws Exception {
        UUID id = submit("delete-copy.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        copies.edit(id, batch(0, new SetMetadataValue(3, "gone")));

        copies.delete(id);

        assertThrows(NoSuchWorkingCopyException.class, () -> copies.info(id));
        assertThrows(NoSuchWorkingCopyException.class, () -> copies.delete(id));
        assertTrue(sessions.find(id).isPresent());
        assertEquals(0, copies.create(id, SourceEncoding.UTF_8).revision(), "a new copy starts from the upload");
    }

    @Test
    void undoAndRedoWithNothingToDoAreConflicts() throws Exception {
        UUID id = submit("undo.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);

        assertThrows(WorkingCopyConflictException.class, () -> copies.undo(id, 0));
        assertThrows(WorkingCopyConflictException.class, () -> copies.redo(id, 0));
        assertThrows(StaleRevisionException.class, () -> copies.undo(id, 5));

        copies.edit(id, batch(0, new SetMetadataValue(3, "x")));
        WorkingCopyInfo undone = copies.undo(id, 1);
        assertEquals(2, undone.revision());
        assertTrue(undone.canRedo());
        assertEquals(3, copies.redo(id, 2).revision());
        assertThrows(IllegalArgumentException.class, () -> copies.edit(id, batch(3, new DeleteLine(100_000))));
    }

    @Test
    void storageCapRefusesCopiesAndEdits() throws Exception {
        UUID id = submit("full.mzTab", example());
        UUID other = submit("full-other.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        long cap = storageProperties.getMaxTotalBytes();
        try {
            storageProperties.setMaxTotalBytes(1);
            assertThrows(StorageFullException.class, () -> copies.create(other, SourceEncoding.UTF_8));
            assertThrows(NoSuchWorkingCopyException.class, () -> copies.info(other));
            assertThrows(StorageFullException.class, () -> copies.edit(id, batch(0, new SetMetadataValue(3, "x"))));
            assertEquals(0, copies.info(id).revision(), "a refused edit changes nothing");
        } finally {
            storageProperties.setMaxTotalBytes(cap);
        }
    }

    @Test
    void anUnreachableEngineFailsTheRun() throws Exception {
        UUID id = submit("down.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        ENGINE.mode(EngineProxy.Mode.DOWN);

        copies.requestValidation(id, false);
        WorkingCopyValidation v = awaitRun(id, 0);

        assertEquals(Status.FAILED, v.status());
        assertEquals(EngineMessages.unavailable("2.0"), v.failureReason());
        assertThrows(IllegalStateException.class, () -> copies.writeJson(id, new ByteArrayOutputStream()));
    }

    @Test
    void aBusyEngineIsAskedAgain() throws Exception {
        UUID id = submit("busy.mzTab", example());
        copies.create(id, SourceEncoding.UTF_8);
        int jobsBefore = ENGINE.jobs().size();
        ENGINE.retryAfterSeconds(1);
        ENGINE.mode(EngineProxy.Mode.BUSY);

        copies.requestValidation(id, false);
        Instant end = Instant.now().plus(TIMEOUT);
        while (ENGINE.jobs().size() == jobsBefore && Instant.now().isBefore(end)) {
            Thread.sleep(20);
        }
        assertEquals(Status.RUNNING, copies.info(id).validation().status(), "waiting for the busy engine");
        ENGINE.mode(EngineProxy.Mode.FORWARD);

        WorkingCopyValidation v = awaitRun(id, 0);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
    }

    // ---- CV rules (a session with a profile) ----

    /** The example as a file of {@code line}. */
    private static String exampleOf(String line) throws IOException {
        String text = example();
        assertTrue(text.contains("MTD\tmzTab-version\t2.0.0-M"));
        return text.replace("MTD\tmzTab-version\t2.0.0-M", "MTD\tmzTab-version\t" + line + ".0-M");
    }

    private static EngineProxy proxyOf(String line) {
        return line.equals("2.0") ? ENGINE : ENGINE_21;
    }

    private static void assumeLine(String line) {
        assumeTrue(line.equals("2.0") || EngineTestSupport.has21(), "the 2.1 engine is not built");
    }

    /** Submits the example of {@code line} with that line's default profile (CV rules) and waits for its end. */
    private UUID submitWithProfile(String name, String line) throws Exception {
        return submitWithProfile(name, exampleOf(line), line, false);
    }

    /**
     * Submits {@code text} with the CV rules of {@code line}'s default profile, as that profile or ({@code custom})
     * as an uploaded mapping file, and waits for its end.
     */
    private UUID submitWithProfile(String name, String text, String line, boolean custom) throws Exception {
        InputStream content = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        UUID id = custom
            ? sessions.submit(name, content, new ValidationOptions(ValidationLevel.INFO, 100, null, null),
                catalog.mappingUrl(catalog.defaultFor(line).orElseThrow()).openStream()).id()
            : sessions.submit(name, content,
                new ValidationOptions(ValidationLevel.INFO, 100, "mztab-m-" + line + "-default", null), null).id();
        Instant end = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(end)) {
            ValidationRecord r = sessions.find(id).orElseThrow();
            if (r.status() == ValidationStatus.FINISHED) {
                assertFalse(cvMessages(r.messages()).isEmpty(), "the session reports CV rule messages: "
                    + r.messages());
                return id;
            }
            assertFalse(r.status() == ValidationStatus.FAILED, "the validation failed: " + r.messages());
            Thread.sleep(50);
        }
        throw new AssertionError("validation " + id + " did not end");
    }

    private static List<String> cvMessages(List<ValidationMessage> messages) {
        return messages.stream().filter(m -> m.getCategory() == ValidationMessage.Category.CROSS_CHECK)
            .map(ValidationMessage::getMessage).toList();
    }

    private static List<String> cvMessagesOf(WorkingCopyValidation v) {
        return cvMessages(v.messages().stream().map(TrackedMessage::message).toList());
    }

    /** Polls until the copy's last run has ended on {@code revision} and is of the kind asked for. */
    private WorkingCopyValidation awaitRun(UUID id, long revision, boolean semantic) throws InterruptedException {
        Instant end = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(end)) {
            WorkingCopyValidation v = copies.info(id).validation();
            if (v != null && v.status() != Status.RUNNING && v.revision() == revision && v.semantic() == semantic) {
                return v;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no ended " + (semantic ? "semantic" : "structural") + " run on revision "
            + revision + ": " + copies.info(id).validation());
    }

    /**
     * The editor's first run is structural; it is compared with the session's messages without the CV ones, and
     * the session's CV messages stay shown, as of revision 0: an unedited copy shows nothing new and nothing fixed.
     */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void firstStructuralRunOfAProfiledSessionMarksNothingNew(String line) throws Exception {
        assumeLine(line);
        UUID id = submitWithProfile("profiled-" + line + ".mzTab", line);
        List<String> sessionCv = cvMessages(sessions.find(id).orElseThrow().messages());

        WorkingCopyInfo created = copies.create(id, SourceEncoding.UTF_8);
        assertEquals(0L, created.cvCheckedRevision(), "the session checked the CV rules of revision 0");

        copies.requestValidation(id, false);
        WorkingCopyValidation v = awaitRun(id, 0, false);

        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertEquals(List.of(), v.messages().stream().filter(t -> t.state() == TrackedMessage.State.NEW)
            .map(t -> t.message().getMessage()).toList(), "nothing is new in an unedited copy");
        assertEquals(List.of(), v.fixed(), "nothing is fixed in an unedited copy");
        assertEquals(sessionCv, cvMessagesOf(v), "the session's CV messages stay shown");
        assertEquals(0L, copies.info(id).cvCheckedRevision());
    }

    /**
     * A structural run keeps the CV messages of the last CV check, never fixes them, and leaves the revision of
     * that check; a semantic run replaces them and compares them with the CV messages before it.
     */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void structuralRunsKeepTheMessagesOfTheLastCvCheck(String line) throws Exception {
        assumeLine(line);
        UUID id = submitWithProfile("kept-" + line + ".mzTab", line);
        List<String> sessionCv = cvMessages(sessions.find(id).orElseThrow().messages());
        copies.create(id, SourceEncoding.UTF_8);

        copies.edit(id, batch(0, new SetMetadataValue(3, "edited-once")));
        copies.requestValidation(id, false);
        WorkingCopyValidation structural = awaitRun(id, 1, false);
        assertEquals(Status.DONE, structural.status(), "failure: " + structural.failureReason());
        assertEquals(sessionCv, cvMessagesOf(structural), "a structural run keeps the CV messages");
        assertEquals(List.of(), cvMessages(structural.fixed()), "nor does it fix any of them");
        assertEquals(0L, copies.info(id).cvCheckedRevision(), "the CV rules were not checked since revision 0");

        copies.requestValidation(id, true);
        WorkingCopyValidation semantic = awaitRun(id, 1, true);
        assertEquals(Status.DONE, semantic.status(), "failure: " + semantic.failureReason());
        assertEquals(1L, copies.info(id).cvCheckedRevision());
        assertEquals(sessionCv, cvMessagesOf(semantic), "the same text has the same CV messages");
        assertTrue(semantic.messages().stream().allMatch(t -> t.state() == TrackedMessage.State.OPEN),
            "each kind is compared with its own, so nothing is new: " + semantic.messages());
        assertEquals(List.of(), semantic.fixed());

        copies.edit(id, batch(1, new SetMetadataValue(3, "edited-twice")));
        copies.requestValidation(id, false);
        WorkingCopyValidation after = awaitRun(id, 2, false);
        assertEquals(sessionCv, cvMessagesOf(after));
        assertEquals(1L, copies.info(id).cvCheckedRevision());
    }

    /** A structural request (the editor's idle check) during a CV check neither replaces it nor runs again. */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void aStructuralRequestDuringACvCheckDoesNotReplaceIt(String line) throws Exception {
        assumeLine(line);
        EngineProxy engine = proxyOf(line);
        UUID id = submitWithProfile("during-" + line + ".mzTab", line);
        copies.create(id, SourceEncoding.UTF_8);
        int jobsBefore = engine.jobs().size();

        EngineProxy.Gate gate = engine.holdNextValidation();
        copies.requestValidation(id, true);
        assertTrue(gate.awaitArrival(TIMEOUT), "the CV check reached the engine");
        copies.requestValidation(id, false);
        gate.release();

        WorkingCopyValidation v = awaitRun(id, 0, true);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        WorkingCopyValidation last = copies.info(id).validation();
        assertEquals(Status.DONE, last.status(), "no other run follows");
        assertTrue(last.semantic());
        assertEquals(1, engine.jobs().subList(jobsBefore, engine.jobs().size()).stream()
            .filter(j -> j.path().startsWith("/engine/v1/validations")).count(),
            "the CV check covers the structural one");
    }

    /** A CV check asked for during a run is not lost when a structural request follows it. */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void aPendingCvCheckIsNotDowngradedByALaterRequest(String line) throws Exception {
        assumeLine(line);
        EngineProxy engine = proxyOf(line);
        UUID id = submitWithProfile("pending-" + line + ".mzTab", line);
        copies.create(id, SourceEncoding.UTF_8);

        int jobsBefore = engine.jobs().size();

        EngineProxy.Gate gate = engine.holdNextValidation();
        copies.requestValidation(id, false);
        assertTrue(gate.awaitArrival(TIMEOUT), "the structural run reached the engine");
        copies.requestValidation(id, true);
        copies.requestValidation(id, false);
        gate.release();

        WorkingCopyValidation v = awaitRun(id, 0, true);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertEquals(Status.DONE, copies.info(id).validation().status(), "no other run follows");
        List<EngineProxy.Recorded> validations = engine.jobs().subList(jobsBefore, engine.jobs().size()).stream()
            .filter(j -> j.path().startsWith("/engine/v1/validations")).toList();
        assertEquals(List.of(false, true), validations.stream().map(j -> j.part("mapping").isPresent()).toList(),
            "the structural run, then exactly one CV check");
    }

    private static boolean notChecked(ValidationMessage m) {
        return ValidationMessage.CV_NOT_CHECKED.equals(m.getCode());
    }

    /** Polls until the copy's last run has ended on {@code revision} and says the CV rules were not checked. */
    private WorkingCopyValidation awaitCvNotChecked(UUID id, long revision) throws InterruptedException {
        Instant end = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(end)) {
            WorkingCopyValidation v = copies.info(id).validation();
            if (v != null && v.status() != Status.RUNNING && v.revision() == revision && v.cvNotChecked() != null) {
                return v;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no run on revision " + revision + " that did not check the CV rules: "
            + copies.info(id).validation());
    }

    /** What a run that did not check the CV rules must leave as it was. */
    private void assertCvKept(UUID id, WorkingCopyValidation v, List<String> before, long cvRevision) {
        assertFalse(v.semantic(), "it is not a CV check");
        assertEquals(before, cvMessagesOf(v), "the CV messages of the last CV check stay");
        assertEquals(List.of(), cvMessages(v.fixed()), "none of them is fixed");
        assertTrue(v.messages().stream().map(TrackedMessage::message).noneMatch(WorkingCopyServiceTest::notChecked),
            "the engine's note is not a message of the list: " + v.messages());
        assertEquals(cvRevision, copies.info(id).cvCheckedRevision());
    }

    /**
     * A CV check whose rules could not be loaded (the engine's check breaks) keeps the CV messages and their
     * revision, says why, and leaves nothing that a later structural run would count as fixed.
     */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void aCvCheckThatBrokeKeepsTheCvMessages(String line) throws Exception {
        assumeLine(line);
        UUID id = submitWithProfile("broke-" + line + ".mzTab", exampleOf(line), line, true);
        List<String> sessionCv = cvMessages(sessions.find(id).orElseThrow().messages());
        assertEquals(0L, copies.create(id, SourceEncoding.UTF_8).cvCheckedRevision());
        // the stored mapping goes bad: the engine's own loader refuses it, so its check of the rules breaks
        Files.writeString(storage.localFile(id, StorageService.SLOT.MAPPINGFILE,
            ValidationSessionService.CUSTOM_MAPPING), MAPPING_WITH_DOCTYPE, StandardCharsets.UTF_8);

        copies.edit(id, batch(0, new SetMetadataValue(3, "edited")));
        copies.requestValidation(id, true);
        WorkingCopyValidation v = awaitCvNotChecked(id, 1);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertTrue(v.cvNotChecked().contains("Mapping file rejected"), v.cvNotChecked());
        assertCvKept(id, v, sessionCv, 0);

        copies.edit(id, batch(1, new SetMetadataValue(3, "edited-again")));
        copies.requestValidation(id, false);
        WorkingCopyValidation after = awaitRun(id, 2, false);
        assertEquals(List.of(), after.fixed(), "nothing the broken check said is fixed now");
        assertCvKept(id, after, sessionCv, 0);
        assertNull(after.cvNotChecked(), "a structural run asked for no CV check");
    }

    /** A CV check of a text that cannot be read into a model is not a CV check: the engine skips the rules. */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void aCvCheckOfATextWithoutModelKeepsTheCvMessages(String line) throws Exception {
        assumeLine(line);
        UUID id = submitWithProfile("no-model-" + line + ".mzTab", line);
        List<String> sessionCv = cvMessages(sessions.find(id).orElseThrow().messages());
        copies.create(id, SourceEncoding.UTF_8);
        assertEquals("instrument[1]-detector", reader.lines(id, 24, 1).get(0).cells().get(1));

        // an empty parameter is an error, and jmzTab-m builds no model of a file with errors
        copies.edit(id, batch(0, new SetMetadataValue(24, "[, , , ]")));
        copies.requestValidation(id, true);
        WorkingCopyValidation v = awaitCvNotChecked(id, 1);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertTrue(v.messages().stream().map(TrackedMessage::message).anyMatch(code("Error-1012")));
        assertCvKept(id, v, sessionCv, 0);
    }

    /** A text the routing refuses (a version no engine serves) is not checked at all: the CV messages stay. */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void aRefusedTextKeepsTheCvMessages(String line) throws Exception {
        assumeLine(line);
        UUID id = submitWithProfile("refused-" + line + ".mzTab", line);
        List<String> sessionCv = cvMessages(sessions.find(id).orElseThrow().messages());
        copies.create(id, SourceEncoding.UTF_8);

        copies.edit(id, batch(0, new SetMetadataValue(2, "9.9.0-M")));
        copies.requestValidation(id, true);
        WorkingCopyValidation v = awaitRun(id, 1, false);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertCvKept(id, v, sessionCv, 0);
        // no engine was asked, so no check of the rules was tried: the refusal's own error says why
        assertNull(v.cvNotChecked(), "a refused text is not a CV check that failed");
        assertTrue(v.messages().stream().anyMatch(t -> t.message().getMessage().contains("9.9")),
            "the refusal names the version: " + v.messages());
    }

    /**
     * A session whose CV check broke (the engine's "Semantic validation failed" error, code {@code cv_not_checked})
     * did not check the CV rules: the copy has no CV check, and the first structural run neither shows the error
     * as a CV message of revision 0 nor counts it as fixed. The session's record is written as such a session
     * leaves it: its structural messages and the engine's error, no CV rule messages. (The webapp refuses at upload
     * a mapping the engine's loader would refuse, so a real session gets there only by an engine-side failure.)
     */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void aSessionWhoseCvCheckBrokeHasNoCvCheck(String line) throws Exception {
        assumeLine(line);
        UUID id = submitWithProfile("session-broke-" + line + ".mzTab", line);
        ValidationMessage failed = new ValidationMessage();
        failed.setCode(ValidationMessage.CV_NOT_CHECKED);
        failed.setCategory(ValidationMessage.Category.CROSS_CHECK);
        failed.setMessageType(ValidationMessage.MessageType.ERROR);
        failed.setLineNumber(-1L);
        failed.setMessage("Semantic validation failed for file 'session-broke.mzTab'\n with message: 'boom'\n");
        ObjectNode record;
        try (InputStream in = storage.read(id, StorageService.SLOT.RECORD, "record.json").orElseThrow()) {
            record = (ObjectNode) json.readTree(in);
        }
        ArrayNode messages = json.createArrayNode();
        record.get("messages").forEach(m -> {
            if (!"cross_check".equals(m.path("category").asText())) {
                messages.add(m);
            }
        });
        messages.add(json.valueToTree(failed));
        record.set("messages", messages);
        storage.write(id, StorageService.SLOT.RECORD, "record.json",
            new ByteArrayInputStream(json.writeValueAsBytes(record)));
        assertTrue(sessions.find(id).orElseThrow().messages().stream().anyMatch(WorkingCopyServiceTest::notChecked),
            "the session's record holds the engine's error");

        assertNull(copies.create(id, SourceEncoding.UTF_8).cvCheckedRevision());
        copies.requestValidation(id, false);
        WorkingCopyValidation v = awaitRun(id, 0, false);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertEquals(List.of(), v.messages().stream().filter(t -> t.state() == TrackedMessage.State.NEW)
            .map(t -> t.message().getMessage()).toList());
        assertEquals(List.of(), v.fixed(), "the error of the broken check is not fixed by a structural run");
        assertEquals(List.of(), cvMessagesOf(v), "no CV messages: the rules were not checked");
        assertNull(copies.info(id).cvCheckedRevision());
    }

    /**
     * A session whose CV rules were skipped (its file has an error, so no model) did not check them: the copy has
     * no CV check, and the engine's note is neither shown nor counted as fixed by the first run.
     */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void aSessionWhoseCvRulesWereSkippedHasNoCvCheck(String line) throws Exception {
        assumeLine(line);
        UUID id = submitWithProfile("skipped-" + line + ".mzTab", exampleOf(line).replace(DETECTOR, "[, , , ]"),
            line, false);
        assertTrue(sessions.find(id).orElseThrow().messages().stream().anyMatch(WorkingCopyServiceTest::notChecked),
            "the engine says it did not check the CV rules");

        assertNull(copies.create(id, SourceEncoding.UTF_8).cvCheckedRevision());
        copies.requestValidation(id, false);
        WorkingCopyValidation v = awaitRun(id, 0, false);
        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        assertEquals(List.of(), v.messages().stream().filter(t -> t.state() == TrackedMessage.State.NEW)
            .map(t -> t.message().getMessage()).toList());
        assertEquals(List.of(), v.fixed());
        assertEquals(List.of(), cvMessagesOf(v), "no CV messages: the rules were not checked");
        assertNull(copies.info(id).cvCheckedRevision());
    }

    private static final String MAPPING_WITH_DOCTYPE =
        org.lifstools.mztab.validator.webapp.service.validation.MappingFixtures.MAPPING_WITH_DOCTYPE;
}
