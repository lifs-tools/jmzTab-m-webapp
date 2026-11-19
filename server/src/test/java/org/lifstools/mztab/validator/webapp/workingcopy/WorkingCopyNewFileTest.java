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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.FieldRule;
import org.lifstools.mztab.validator.engine.api.FieldRules;
import org.lifstools.mztab.validator.engine.api.SkeletonRequest;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.AwaitAsyncValidations;
import org.lifstools.mztab.validator.webapp.EngineProxy;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineUnavailableException;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.storage.FileSystemStorageService;
import org.lifstools.mztab.validator.webapp.session.ProfileRef;
import org.lifstools.mztab.validator.webapp.session.StorageFullException;
import org.lifstools.mztab.validator.webapp.session.ValidationOptions;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.session.ValidationStatus;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * New working copies, made from the engine's skeleton with their own settings and no validation session, and the
 * "still missing" rules of a copy. The real mzTab-M 2.0 engine sits behind an {@link EngineProxy}, which records the
 * requests and answers the field rules with canned results where a test needs exact rules.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
@ExtendWith(AwaitAsyncValidations.class)
class WorkingCopyNewFileTest {

    static final EngineProxy ENGINE = EngineProxy.start();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String DEFAULT_20 = "mztab-m-2.0-default";
    private static final ValidationOptions STRUCTURAL = new ValidationOptions(ValidationLevel.INFO, 100, null, null);

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
    ValidationSessionService sessions;

    @Autowired
    EngineRegistry registry;

    @Autowired
    ObjectMapper json;

    @MockitoSpyBean
    StorageService storage;

    @AfterEach
    void forward() {
        ENGINE.reset();
        reset(storage);
    }

    /** A new 2.0 file with the 2.0 default rules of version 1.0.0 (not the latest, 1.1.0). */
    private static NewFile newFile(String mzTabId) {
        return new NewFile("2.0.0-M", null, DEFAULT_20, "1.0.0", mzTabId);
    }

    private String text(UUID id) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copies.writeTsv(id, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = WorkingCopyNewFileTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    private Set<UUID> slots() {
        return storage.sessions().collect(Collectors.toSet());
    }

    private List<EngineProxy.Recorded> jobs(String pathPrefix) {
        return ENGINE.jobs().stream().filter(j -> j.path().startsWith(pathPrefix)).toList();
    }

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

    private static FieldRule rule(String id, String requirement, Boolean present) {
        return new FieldRule(id, "rule " + id, "/metadata/" + id, requirement, "OR", List.of(), present);
    }

    private void stubFieldRules(boolean parsed, FieldRule... rules) throws IOException {
        ENGINE.stub("/engine/v1/field-rules", 200, "application/json",
            json.writeValueAsString(new FieldRules(parsed, List.of(rules))));
    }

    @Test
    void createNewStoresSkeletonAndSettings() throws Exception {
        String skeleton = registry.forLine("2.0").orElseThrow()
            .skeleton(new SkeletonRequest("2.0.0-M", null, "my-study"));

        WorkingCopyInfo info = copies.createNew(newFile("my-study"));

        assertEquals(skeleton, text(info.id()), "the copy's text is the engine's skeleton");
        assertEquals("MTD\tmzTab-version\t2.0.0-M\nMTD\tmzTab-ID\tmy-study\n", skeleton);
        assertEquals("new", info.origin());
        assertEquals("my-study.mzTab", info.fileName());
        assertEquals(ProfileRef.catalog(DEFAULT_20, "1.0.0"), info.validationProfile());
        assertEquals("2.0.0-M", info.mzTabVersion());
        assertEquals(0, info.revision());
        assertEquals(2, info.lineCount());
        assertTrue(sessions.find(info.id()).isEmpty(), "a new copy has no validation session");
        assertEquals(info, copies.info(info.id()));
    }

    @Test
    void newCopyReopensWithoutSession() {
        UUID id = copies.createNew(newFile("reopen")).id();
        assertTrue(sessions.find(id).isEmpty());

        WorkingCopyService.Creation again = copies.createOrFind(id, SourceEncoding.UTF_8);

        assertFalse(again.created());
        assertEquals(id, again.info().id());
        assertEquals("new", again.info().origin());
    }

    @Test
    void newCopyValidatesWithItsOwnProfile() throws Exception {
        UUID id = copies.createNew(newFile("own-rules")).id();

        copies.requestValidation(id, true);
        WorkingCopyValidation v = awaitRun(id, 0);

        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        List<EngineProxy.Recorded> validations = jobs("/engine/v1/validations");
        assertEquals(1, validations.size());
        EngineProxy.Recorded job = validations.get(0);
        assertArrayEquals(resource("/validation-profiles/" + DEFAULT_20 + "/1.0.0.xml"),
            job.part("mapping").orElseThrow(), "the chosen catalog profile's mapping, not the latest default");
        assertTrue(job.path().contains("level=INFO") && job.path().contains("maxErrors=100"),
            "the validator form's defaults: " + job.path());
    }

    @Test
    void profileOfOtherVersionRefused() {
        Set<UUID> before = slots();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> copies.createNew(new NewFile("2.0.0-M", null, "mztab-m-2.1-default", "1.0.0", "other")));

        assertTrue(e.getMessage().contains("mztab-m-2.1-default") && e.getMessage().contains("2.0.0-M"),
            e.getMessage());
        assertEquals(before, slots(), "nothing is stored");
        assertTrue(jobs("/engine/v1/skeletons").isEmpty(), "the engine is not asked");
    }

    @Test
    void badRequestsAreRefusedBeforeTheEngine() {
        Set<UUID> before = slots();
        List<NewFile> bad = List.of(
            new NewFile("9.9.0-M", null, DEFAULT_20, "1.0.0", "no-engine"),
            new NewFile("", null, DEFAULT_20, "1.0.0", "no-version"),
            new NewFile("2.0.0-M", "M", DEFAULT_20, "1.0.0", "profile-for-two-zero"),
            new NewFile("2.0.0-M", null, DEFAULT_20, "1.0.0", "-starts-with-a-dash"),
            new NewFile("2.0.0-M", null, DEFAULT_20, "1.0.0", "x".repeat(65)),
            new NewFile("2.0.0-M", null, "no-such-rules", "1.0.0", "unknown-rules"),
            new NewFile("2.0.0-M", null, DEFAULT_20, "9.9.9", "unknown-rules-version"));
        for (NewFile f : bad) {
            assertThrows(IllegalArgumentException.class, () -> copies.createNew(f), f.toString());
        }
        assertEquals(before, slots(), "nothing is stored");
        assertTrue(jobs("/engine/v1/skeletons").isEmpty(), "the engine is not asked");
    }

    @Test
    void aTextWithoutTheVersionLineIsNotStored() {
        Set<UUID> before = slots();
        ENGINE.stub("/engine/v1/skeletons", 200, "text/tab-separated-values", "MTD\tmzTab-ID\tx\n");

        assertThrows(EngineUnavailableException.class, () -> copies.createNew(newFile("no-version-line")));

        assertEquals(before, slots(), "nothing is stored");
    }

    @Test
    void createNewChecksCapacity() {
        Set<UUID> before = slots();
        doReturn(Long.MAX_VALUE).when(storage).totalBytes();

        assertThrows(StorageFullException.class, () -> copies.createNew(newFile("full")));

        assertEquals(before, slots(), "nothing is stored");
    }

    @Test
    void uploadCopyStillUsesSession() throws Exception {
        byte[] mapping = resource("/validation-profiles/" + DEFAULT_20 + "/1.0.0.xml");
        UUID id = sessions.submit("custom.mzTab",
            new ByteArrayInputStream(resource("/metabolomics/lipidomics-example.mzTab")), STRUCTURAL,
            new ByteArrayInputStream(mapping)).id();
        Instant end = Instant.now().plus(TIMEOUT);
        while (sessions.find(id).orElseThrow().status() != ValidationStatus.FINISHED) {
            assertTrue(Instant.now().isBefore(end), "the validation did not end");
            assertFalse(sessions.find(id).orElseThrow().status() == ValidationStatus.FAILED);
            Thread.sleep(50);
        }
        WorkingCopyInfo info = copies.create(id, SourceEncoding.UTF_8);
        assertEquals("upload", info.origin());
        assertEquals(ProfileRef.custom(), info.validationProfile());
        ENGINE.reset();

        copies.requestValidation(id, true);
        WorkingCopyValidation v = awaitRun(id, 0);

        assertEquals(Status.DONE, v.status(), "failure: " + v.failureReason());
        List<EngineProxy.Recorded> validations = jobs("/engine/v1/validations");
        assertEquals(1, validations.size());
        assertArrayEquals(mapping, validations.get(0).part("mapping").orElseThrow(),
            "the session's custom mapping");
    }

    @Test
    void stillMissingListsUnmetRules() throws Exception {
        UUID id = copies.createNew(newFile("missing")).id();
        stubFieldRules(true, rule("A", "MUST", false), rule("B", "SHOULD", false), rule("C", "MUST", true),
            rule("D", "MAY", false), rule("E", "MUST", null), rule("F", null, false), rule("G", "SHOULD", true));

        StillMissing missing = copies.stillMissing(id);

        assertTrue(missing.checked());
        assertEquals(0, missing.revision());
        assertEquals(List.of("A", "B"), missing.missing().stream().map(FieldRule::id).toList(),
            "MUST then SHOULD rules not met; a rule of unknown presence (E) or without a level (F) is not missing");
        List<EngineProxy.Recorded> asked = jobs("/engine/v1/field-rules");
        assertEquals(1, asked.size());
        assertArrayEquals(resource("/validation-profiles/" + DEFAULT_20 + "/1.0.0.xml"),
            asked.get(0).part("mapping").orElseThrow(), "the copy's own rules");
        assertEquals(text(id), new String(asked.get(0).part("file").orElseThrow(), StandardCharsets.UTF_8),
            "the text of the current revision");

        assertEquals(missing, copies.stillMissing(id));
        assertEquals(1, jobs("/engine/v1/field-rules").size(), "the result of a revision is cached");
    }

    @Test
    void stillMissingUnknownWhenNotParsed() throws Exception {
        UUID id = copies.createNew(newFile("unknown")).id();
        stubFieldRules(false, rule("A", "SHOULD", null), rule("B", "MUST", null), rule("C", "MAY", null),
            rule("D", "MUST", null), rule("E", null, null));

        StillMissing missing = copies.stillMissing(id);

        assertFalse(missing.checked());
        assertEquals(List.of("B", "D", "A"), missing.missing().stream().map(FieldRule::id).toList(),
            "all MUST rules, then all SHOULD rules: their presence is unknown");
    }

    @Test
    void stillMissingOfAFreshSkeletonListsTheRequiredRules() {
        UUID id = copies.createNew(newFile("fresh")).id();

        StillMissing missing = copies.stillMissing(id);

        // jmzTab-m reads no model from a skeleton (software, assay and cv MUST be defined)
        assertFalse(missing.checked());
        assertFalse(missing.missing().isEmpty());
        List<String> levels = missing.missing().stream().map(FieldRule::requirement).toList();
        assertTrue(levels.stream().allMatch(l -> l.equals("MUST") || l.equals("SHOULD")), levels.toString());
        int firstShould = levels.indexOf("SHOULD");
        assertTrue(firstShould < 0 || !levels.subList(firstShould, levels.size()).contains("MUST"),
            "MUST first: " + levels);
    }

    @Test
    void stillMissingFollowsTheRevision() throws Exception {
        UUID id = copies.createNew(newFile("follows")).id();
        stubFieldRules(true, rule("A", "MUST", false));
        assertEquals(0, copies.stillMissing(id).revision());

        copies.edit(id, new EditBatch(0, List.of(new EditOperation.SetMetadataValue(2, "follows-2"))));

        assertEquals(1, copies.stillMissing(id).revision());
        assertEquals(2, jobs("/engine/v1/field-rules").size(), "a new revision is asked again");
    }

    @Test
    void stillMissingOfNoCopy() {
        assertThrows(NoSuchWorkingCopyException.class, () -> copies.stillMissing(UUID.randomUUID()));
    }

    @Test
    void newCopyIsCleanedUpByAge() throws Exception {
        UUID id = copies.createNew(newFile("old")).id();
        Path slot = storageRoot.resolve(id.toString());
        assertTrue(Files.isDirectory(slot));
        Files.setLastModifiedTime(slot, FileTime.from(Instant.now().minus(Duration.ofDays(8))));

        ((FileSystemStorageService) storage).deleteOldFiles();

        assertFalse(Files.exists(slot), "the storage maintenance deletes the slot without a session record");
        assertThrows(NoSuchWorkingCopyException.class, () -> copies.info(id));
        assertNotNull(copies.createNew(newFile("fresh-after-cleanup")));
    }
}
