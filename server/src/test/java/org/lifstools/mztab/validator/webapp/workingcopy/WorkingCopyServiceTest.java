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
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.lifstools.mztab.validator.webapp.engine.EngineMessages;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.guidance.GuidanceCatalog;
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
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
 * the overlap of a run and an edit deterministic.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ExtendWith(AwaitAsyncValidations.class)
class WorkingCopyServiceTest {

    static final EngineProxy ENGINE = EngineProxy.start();
    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DETECTOR = "[MS, MS:1000624, inductive detector, ]";

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        ENGINE.register(registry);
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

    @AfterEach
    void forward() {
        ENGINE.reset();
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
}
