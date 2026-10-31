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
package org.lifstools.mztab.validator.webapp.workingcopy.quickfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineTestSupport;
import org.lifstools.mztab.validator.webapp.guidance.GuidanceCatalog;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.session.ValidationStatus;
import org.lifstools.mztab.validator.webapp.workingcopy.SourceEncoding;
import org.lifstools.mztab.validator.webapp.workingcopy.StaleRevisionException;
import org.lifstools.mztab.validator.webapp.workingcopy.TrackedMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyConflictException;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyReader;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The quick fixes against the real mzTab-M engines, 2.0 and 2.1 (spec section 6): each fixture has the
 * problem of one fix; the preview batch is applied and the engine must no longer report the message. The
 * fixtures are 2.0 files; for the 2.1 engine their {@code mzTab-version} line says {@code 2.1.0-M}, which routes
 * them there. The 2.1 cases run whenever the build has the 2.1 engine (it is left out only with
 * {@code -DskipSnapshotEngines}).
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ExtendWith(AwaitAsyncValidations.class)
class QuickFixEngineTest {

    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
        EngineTestSupport.registerBoth(registry);
    }

    @Autowired
    WorkingCopyService copies;

    @Autowired
    WorkingCopyReader reader;

    @Autowired
    QuickFixService fixes;

    @Autowired
    ValidationSessionService sessions;

    private static String resource(String name) throws IOException {
        try (InputStream in = QuickFixEngineTest.class.getResourceAsStream("/workingcopy/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The fixture as a file of {@code line}: its version line names that line's version. */
    private static String resource(String name, String line) throws IOException {
        String text = resource(name);
        if (line.equals("2.0")) {
            return text;
        }
        String version = "MTD\tmzTab-version\t2.0.0-M";
        assertTrue(text.contains(version), name);
        return text.replace(version, "MTD\tmzTab-version\t" + line + ".0-M");
    }

    /** The engine of {@code line} is in this build; 2.0 always is. */
    private static void requireEngine(String line) {
        assumeTrue(line.equals("2.0") || EngineTestSupport.has21(),
            "the mzTab-M 2.1 engine is not built (-DskipSnapshotEngines)");
    }

    /** Asserts that the session was validated by the engine of {@code line}. */
    private void validatedBy(UUID id, String line) {
        String engine = sessions.find(id).orElseThrow().engine();
        assertTrue(engine != null && engine.startsWith("mzTab-M " + line + " "), "validated by " + engine);
    }

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

    private WorkingCopyValidation validate(UUID id, long revision, boolean semantic) throws InterruptedException {
        copies.requestValidation(id, semantic);
        Instant end = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(end)) {
            WorkingCopyValidation v = copies.info(id).validation();
            if (v != null && v.status() != Status.RUNNING && v.revision() == revision) {
                assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
                return v;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no ended run on revision " + revision);
    }

    private static int indexOf(WorkingCopyValidation v, String typeCode) {
        for (int i = 0; i < v.messages().size(); i++) {
            if (GuidanceCatalog.typeCodeOf(v.messages().get(i).message()).equals(typeCode)) {
                return i;
            }
        }
        throw new AssertionError(typeCode + " not reported: " + v.messages().stream()
            .map(t -> t.message().getCode()).toList());
    }

    private static boolean has(List<TrackedMessage> messages, String typeCode) {
        return messages.stream().anyMatch(t -> GuidanceCatalog.typeCodeOf(t.message()).equals(typeCode));
    }

    /**
     * Validates the fixture, previews and applies the fix for the first message with the code, validates again
     * and checks that this message is among the fixed ones and no longer reported. Without a follow-up, no
     * error may remain and the file must be read to its end; with one, the follow-up code is reported.
     */
    private void fixesIt(String line, String file, String fixId, String typeCode, boolean semantic, String next)
        throws Exception {
        UUID id = submit(file, resource(file, line).getBytes(StandardCharsets.UTF_8));
        validatedBy(id, line);
        copies.create(id, SourceEncoding.UTF_8);
        WorkingCopyValidation before = validate(id, 0, semantic);
        int message = indexOf(before, typeCode);
        ValidationMessage target = before.messages().get(message).message();

        QuickFixPreview preview = fixes.preview(id, fixId, message, false);

        assertEquals(fixId, preview.fixId());
        assertEquals(0, preview.revision());
        assertFalse(preview.changes().isEmpty());
        assertEquals(0, copies.info(id).revision(), "a preview changes nothing");
        assertFalse(copies.info(id).canUndo());
        copies.edit(id, preview.batch());
        WorkingCopyValidation after = validate(id, 1, semantic);
        assertFalse(after.messages().stream().map(TrackedMessage::message)
            .anyMatch(m -> same(m, target)), "still reported: " + after.messages());
        assertTrue(after.fixed().stream().anyMatch(m -> same(m, target)), "not among the fixed: " + after.fixed());
        if (next == null) {
            assertTrue(after.parsed(), "the file is read to the end");
            assertTrue(after.messages().stream().map(TrackedMessage::message)
                .noneMatch(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR), "errors: " + after.messages());
        } else {
            assertTrue(has(after.messages(), next), "expected the follow-up " + next + ": " + after.messages());
        }
    }

    private static boolean same(ValidationMessage a, ValidationMessage b) {
        return GuidanceCatalog.typeCodeOf(a).equals(GuidanceCatalog.typeCodeOf(b))
            && java.util.Objects.equals(a.getMessage(), b.getMessage());
    }

    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void eachFixRemovesItsMessage(String line) throws Exception {
        requireEngine(line);
        fixesIt(line, "engine-remove-empty-parameter.mzTab", "remove-empty-parameter", "Error-1012", false, null);
        fixesIt(line, "engine-normalise-doi.mzTab", "normalise-doi", "Error-1014", false, null);
        fixesIt(line, "engine-join-broken-line.mzTab", "join-broken-line", "Error-1000", false, null);
        fixesIt(line, "engine-split-mtd-line.mzTab", "split-mtd-line", "Error-1008", false, null);
        fixesIt(line, "engine-add-mandatory-column.mzTab", "add-mandatory-column", "Error-1004", false, null);
        fixesIt(line, "engine-add-missing-metadata.mzTab", "add-missing-metadata", "Error-2008", false,
            // the inserted line has an empty value, which the engine reports in turn: the value is the user's to give
            "Error-1008");
        fixesIt(line, "engine-remove-unused-cv.mzTab", "remove-unused-cv", "Warn-3014", true, null);
    }

    /**
     * A header that names a column twice is reported as Error-2062; merging the two columns (no quick fix, the
     * editor's duplicate-column resolution) removes the message.
     */
    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void mergingDuplicateColumnsFixesTheMessage(String line) throws Exception {
        requireEngine(line);
        String file = "engine-duplicate-column.mzTab";
        UUID id = submit(file, resource(file, line).getBytes(StandardCharsets.UTF_8));
        validatedBy(id, line);
        copies.create(id, SourceEncoding.UTF_8);
        WorkingCopyValidation before = validate(id, 0, false);
        ValidationMessage duplicate = before.messages().get(indexOf(before, "Error-2062")).message();
        assertEquals(70L, duplicate.getLineNumber());
        List<org.lifstools.mztab.validator.webapp.workingcopy.DuplicateColumn> found = reader.duplicates(id, "SMH");
        assertEquals(1, found.size());
        assertEquals(List.of(17, 20), found.get(0).positions());

        copies.edit(id, new org.lifstools.mztab.validator.webapp.workingcopy.EditBatch(0, List.of(
            new org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.MergeColumns("SMH",
                found.get(0).name(), 17, List.of(20)))));

        WorkingCopyValidation after = validate(id, 1, false);
        assertFalse(has(after.messages(), "Error-2062"), "still reported: " + after.messages());
        assertTrue(after.fixed().stream().anyMatch(m -> same(m, duplicate)), "not among the fixed: " + after.fixed());
        assertTrue(after.messages().stream().map(TrackedMessage::message)
            .noneMatch(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR), "errors: " + after.messages());
        assertTrue(reader.duplicates(id, "SMH").isEmpty());
    }

    @Test
    void reencodeLatin1() throws Exception {
        String text = resource("engine-remove-empty-parameter.mzTab").replace("ISAS-2018-1234", "IS\u00e9S-2018-1234");
        UUID id = submit("latin1.mzTab", text.getBytes(StandardCharsets.ISO_8859_1));

        assertThrows(org.lifstools.mztab.validator.webapp.workingcopy.NotUtf8Exception.class,
            () -> copies.create(id, SourceEncoding.UTF_8));
        copies.create(id, SourceEncoding.WINDOWS_1252);

        assertTrue(reader.lines(id, 3, 1).get(0).text().contains("IS\u00e9S"));
        assertEquals(Status.DONE, validate(id, 0, false).status());
        // there is no text edit for it
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> fixes.preview(id, "reencode-latin1", 0, false));
        assertTrue(e.getMessage().contains("WINDOWS_1252"), e.getMessage());
    }

    @ParameterizedTest(name = "mzTab-M {0}")
    @ValueSource(strings = {"2.0", "2.1"})
    void allFixesEveryMessageOfTheGroup(String line) throws Exception {
        requireEngine(line);
        String text = resource("engine-remove-empty-parameter.mzTab", line)
            .replace("[MS, MS:1001838, SRM quantitation analysis, ]", "[, , , ]");
        UUID id = submit("two-empty.mzTab", text.getBytes(StandardCharsets.UTF_8));
        validatedBy(id, line);
        copies.create(id, SourceEncoding.UTF_8);
        WorkingCopyValidation v = validate(id, 0, false);
        int message = indexOf(v, "Error-1012");

        QuickFixPreview one = fixes.preview(id, "remove-empty-parameter", message, false);
        QuickFixPreview all = fixes.preview(id, "remove-empty-parameter", message, true);

        assertEquals(1, one.occurrences());
        assertEquals(2, all.occurrences());
        assertEquals(2, all.batch().operations().size());
        assertEquals(2, all.changes().size());
        copies.edit(id, all.batch());
        assertFalse(has(validate(id, 1, false).messages(), "Error-1012"));
    }

    @Test
    void previewNeedsAFreshValidation() throws Exception {
        UUID id = submit("fresh.mzTab", resource("engine-remove-empty-parameter.mzTab").getBytes(StandardCharsets.UTF_8));
        copies.create(id, SourceEncoding.UTF_8);
        WorkingCopyConflictException none = assertThrows(WorkingCopyConflictException.class,
            () -> fixes.preview(id, "remove-empty-parameter", 0, false));
        assertTrue(none.getMessage().contains("Validate the current revision first."));

        WorkingCopyValidation v = validate(id, 0, false);
        int message = indexOf(v, "Error-1012");
        QuickFixPreview preview = fixes.preview(id, "remove-empty-parameter", message, false);
        assertEquals(copies.info(id).revision(), preview.batch().revision());

        assertThrows(IllegalArgumentException.class, () -> fixes.preview(id, "remove-empty-parameter", -1, false));
        assertThrows(IllegalArgumentException.class,
            () -> fixes.preview(id, "remove-empty-parameter", v.messages().size(), false));
        assertThrows(IllegalArgumentException.class, () -> fixes.preview(id, "no-such-fix", message, false));
        assertThrows(IllegalArgumentException.class, () -> fixes.preview(id, "../etc", message, false));
        // a message of another kind: the fix does not apply
        assertThrows(IllegalArgumentException.class, () -> fixes.preview(id, "normalise-doi", message, false));
        // not for a group
        assertThrows(IllegalArgumentException.class, () -> fixes.preview(id, "remove-unused-cv", message, true));

        // any edit makes the validation stale; a preview made before it is refused when applied
        copies.edit(id, new org.lifstools.mztab.validator.webapp.workingcopy.EditBatch(0,
            List.of(new org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.SetMetadataValue(3, "x"))));
        assertThrows(WorkingCopyConflictException.class, () -> fixes.preview(id, "remove-empty-parameter", message, false));
        assertThrows(StaleRevisionException.class, () -> copies.edit(id, preview.batch()));
    }
}
