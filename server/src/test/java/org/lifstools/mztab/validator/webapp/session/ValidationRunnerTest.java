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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab.validator.webapp.sections.SectionWriter;
import org.lifstools.mztab.validator.webapp.service.AnalyticsTracker;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.validation.MzTabMValidator;
import org.lifstools.mztab2.io.MzTabFileParser;
import org.lifstools.mztab2.model.MzTab;
import org.lifstools.mztab2.model.ValidationMessage;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;
import uk.ac.ebi.pride.utilities.ols.web.service.client.OLSClient;

/**
 * The runner on the calling thread: JSON uploads, server-side tracking and a
 * failing section write.
 */
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class ValidationRunnerTest {

    private static final String LIPIDOMICS = "/metabolomics/lipidomics-example.mzTab";

    @TempDir
    static Path storageRoot;

    @TempDir
    Path work;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("storage.location", () -> storageRoot.toString());
    }

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    OLSClient olsClient;

    @MockitoBean
    AnalyticsTracker tracker;

    @MockitoSpyBean
    SectionWriter sectionWriter;

    @MockitoSpyBean
    MzTabMValidator validator;

    @Autowired
    ValidationRunner runner;

    @Autowired
    ValidationRecordStore records;

    @Autowired
    StorageService storage;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void jsonUploadIsValidatedLikeTsv() throws Exception {
        ValidationRecord tsv = run(resourceBytes(LIPIDOMICS));
        ValidationRecord json = run(objectMapper.writeValueAsBytes(parse(LIPIDOMICS)));

        assertEquals(ValidationStatus.FINISHED, json.status(), "failureReason: " + json.failureReason());
        assertNotEquals(ValidationOutcome.NOT_PARSED, json.outcome(), "messages: " + json.messages());
        assertEquals("2.0.0-M", json.mzTabVersion());
        assertNotNull(json.sections(), "a parsed JSON file has sections");
        assertEquals(tsv.sections(), json.sections());
        assertTrue(storage.read(json.id(), SLOT.SECTIONS, "metadata.jsonl").isPresent());
    }

    @Test
    void jsonWithLeadingWhitespaceIsJson() throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(parse(LIPIDOMICS));
        byte[] padded = new byte[body.length + 3];
        padded[0] = ' ';
        padded[1] = '\n';
        padded[2] = '\t';
        System.arraycopy(body, 0, padded, 3, body.length);

        ValidationRecord record = run(padded);

        assertEquals(ValidationStatus.FINISHED, record.status());
        assertNotNull(record.sections());
    }

    @Test
    void malformedJsonIsNotParsed() throws Exception {
        ValidationRecord record = run("{\"metadata\": {\"mzTab-version\": \"2.0.0-M\",".getBytes(StandardCharsets.UTF_8));

        assertEquals(ValidationOutcome.NOT_PARSED, record.outcome());
        assertTrue(record.status() == ValidationStatus.FINISHED || record.status() == ValidationStatus.FAILED);
        assertEquals(1, record.messages().size(), "messages: " + record.messages());
        ValidationMessage message = record.messages().get(0);
        assertEquals(ValidationMessage.MessageTypeEnum.ERROR, message.getMessageType());
        assertTrue(message.getMessage().startsWith("The JSON file could not be read: "), message.getMessage());
        assertFalse(message.getMessage().contains("\tat "), "no stack trace: " + message.getMessage());
        assertTrue(message.getMessage().length() <= 600, message.getMessage());
        assertNull(record.sections());
        assertEquals(1, record.counts().get(ValidationLevel.ERROR));
        verify(validator, never()).validateAndParse(any(), any(), anyInt(), any());
    }

    @Test
    void jsonThatIsNoMzTabModelIsReportedNotFailed() throws Exception {
        for (String body : List.of("{}", "{\"noSuchField\": 1}")) {
            ValidationRecord record = run(body.getBytes(StandardCharsets.UTF_8));

            assertEquals(ValidationStatus.FINISHED, record.status(), body + ": " + record.failureReason());
            assertEquals(ValidationOutcome.NOT_PARSED, record.outcome(), body);
            assertFalse(record.messages().isEmpty(), body);
        }
    }

    @Test
    void legacyJsonIsRejected() throws Exception {
        MzTab mzTab = parse(LIPIDOMICS);
        mzTab.getMetadata().setMzTabVersion("1.0.0");

        ValidationRecord record = run(objectMapper.writeValueAsBytes(mzTab));

        assertEquals(ValidationStatus.FAILED, record.status());
        assertEquals(ValidationOutcome.NOT_PARSED, record.outcome());
        assertEquals("1.0.0", record.mzTabVersion());
        assertEquals(ValidationRunner.MZTAB_1_0, record.messages().get(0).getMessage());
        verify(tracker).stopped(record.id(), "validation", "fail");
    }

    @Test
    void successfulRunIsTracked() throws Exception {
        ValidationRecord record = run(resourceBytes(LIPIDOMICS));

        assertEquals(ValidationStatus.FINISHED, record.status());
        verify(tracker).started(record.id(), "validation", "init");
        verify(tracker).stopped(record.id(), "validation", "success");
        verify(tracker, never()).stopped(record.id(), "validation", "fail");
    }

    @Test
    void failedRunIsTracked() throws Exception {
        UUID id = UUID.randomUUID();
        // no upload: the run fails unexpectedly
        records.write(queued(id));

        AopTestUtils.<ValidationRunner>getTargetObject(runner).run(id);

        ValidationRecord record = records.read(id).orElseThrow();
        assertEquals(ValidationStatus.FAILED, record.status());
        verify(tracker).started(id, "validation", "init");
        verify(tracker).stopped(id, "validation", "fail");
        verify(tracker, never()).stopped(id, "validation", "success");
    }

    @Test
    void failingTrackerDoesNotFailTheValidation() throws Exception {
        doThrow(new IllegalStateException("tracker down")).when(tracker).started(any(), anyString(), anyString());
        doThrow(new IllegalStateException("tracker down")).when(tracker).stopped(any(), anyString(), anyString());

        ValidationRecord record = run(resourceBytes(LIPIDOMICS));

        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
    }

    @Test
    void sectionWriteFailureKeepsTheValidationResult() throws Exception {
        AtomicReference<MzTabMValidator.Outcome> validated = new AtomicReference<>();
        doAnswer(invocation -> {
            MzTabMValidator.Outcome outcome = (MzTabMValidator.Outcome) invocation.callRealMethod();
            validated.set(outcome);
            return outcome;
        }).when(validator).validateAndParse(any(), any(), anyInt(), any());
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            // a partial write: one file made it to storage
            storage.write(id, SLOT.SECTIONS, "metadata.jsonl",
                new ByteArrayInputStream("{}\n".getBytes(StandardCharsets.UTF_8)));
            throw new UncheckedIOException(new IOException("disk full"));
        }).when(sectionWriter).write(any(), any());

        ValidationRecord record = run(resourceBytes(LIPIDOMICS));

        verify(sectionWriter).write(any(), any());
        assertEquals(ValidationStatus.FINISHED, record.status(), "failureReason: " + record.failureReason());
        assertNull(record.failureReason());
        assertNotEquals(ValidationOutcome.NOT_PARSED, record.outcome());
        assertNull(record.sections());
        assertNotNull(validated.get());
        assertFalse(validated.get().messages().isEmpty(), "the fixture produces messages");
        assertEquals(ValidationRunner.outcomeOf(validated.get()), record.outcome());
        assertEquals(validated.get().messages().stream().map(ValidationMessage::getMessage).toList(),
            record.messages().stream().map(ValidationMessage::getMessage).toList());
        assertTrue(storage.read(record.id(), SLOT.SECTIONS, "metadata.jsonl").isEmpty(),
            "partial section files must be deleted");
        assertTrue(storage.read(record.id(), SLOT.MZTABFILE, ValidationSessionService.UPLOAD).isPresent(),
            "the rest of the session stays");
    }

    private ValidationRecord run(byte[] upload) {
        UUID id = UUID.randomUUID();
        storage.write(id, SLOT.MZTABFILE, ValidationSessionService.UPLOAD, new ByteArrayInputStream(upload));
        records.write(queued(id));
        // the target, not the @Async proxy: runs on this thread
        AopTestUtils.<ValidationRunner>getTargetObject(runner).run(id);
        return records.read(id).orElseThrow();
    }

    private static ValidationRecord queued(UUID id) {
        Instant now = Instant.now();
        return ValidationRecord.queued(id, "f.mzTab", ValidationLevel.INFO, 100, ProfileRef.none(), now,
            now.plus(7, ChronoUnit.DAYS));
    }

    private MzTab parse(String resource) throws IOException {
        Path file = work.resolve("fixture.mzTab");
        Files.write(file, resourceBytes(resource));
        MzTabFileParser parser = new MzTabFileParser(file.toFile());
        parser.parse(System.out, MZTabErrorType.findLevel("INFO"), 100);
        MzTab mzTab = parser.getMZTabFile();
        assertNotNull(mzTab, resource);
        return mzTab;
    }

    private byte[] resourceBytes(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }
}
