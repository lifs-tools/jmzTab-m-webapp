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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.webapp.sections.SectionWriter;
import org.lifstools.mztab.validator.webapp.service.AnalyticsTracker;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.validation.MzTabMValidator;
import org.lifstools.mztab.validator.webapp.service.validation.MzTabVersionDetector;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.lifstools.mztab2.io.MzTabNonValidatingWriter;
import org.lifstools.mztab2.model.MzTab;
import org.lifstools.mztab2.model.ValidationMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 * Runs a queued validation: version check, structural and semantic
 * validation with jmzTab-m, section data, and the persisted result. A JSON
 * upload (an mzTab-M model as JSON) is written as tab separated mzTab-M first
 * and then validated like a tab separated upload.
 */
@Slf4j
@Component
public class ValidationRunner {

    static final String MZTAB_1_0 = "This file declares mzTab-version 1.0. mzTab 1.0 is no longer supported; "
        + "the validator checks mzTab-M 2.0 files.";
    static final String UNEXPECTED_ERROR = "The validation could not be completed because of an unexpected "
        + "server error. Please try again later.";
    static final String JSON_NOT_READ = "The JSON file could not be read: ";
    static final String JSON_NOT_CONVERTED = "The JSON file could not be converted to mzTab-M: ";
    /** Longest parser message kept after {@link #JSON_NOT_READ}. */
    private static final int MAX_PARSER_MESSAGE = 500;

    private final ValidationRecordStore records;
    private final StorageService storage;
    private final MzTabMValidator validator;
    private final ValidationProfileCatalog catalog;
    private final SectionWriter sectionWriter;
    private final ObjectMapper objectMapper;
    private final AnalyticsTracker tracker;

    public ValidationRunner(ValidationRecordStore records, StorageService storage, MzTabMValidator validator,
        ValidationProfileCatalog catalog, SectionWriter sectionWriter, ObjectMapper objectMapper,
        AnalyticsTracker tracker) {
        this.records = records;
        this.storage = storage;
        this.validator = validator;
        this.catalog = catalog;
        this.sectionWriter = sectionWriter;
        this.objectMapper = objectMapper;
        this.tracker = tracker;
    }

    /**
     * Runs the validation if it is still {@link ValidationStatus#QUEUED};
     * concurrent or repeated calls for the same id run it once.
     */
    @Async("toolThreadPoolTaskExecutor")
    public void run(UUID id) {
        Optional<ValidationRecord> started = records.update(id,
            r -> r.status() == ValidationStatus.QUEUED ? r.running(Instant.now()) : null);
        if (started.isEmpty()) {
            return;
        }
        ValidationRecord running = started.get();
        ValidationRecord result = null;
        track(() -> tracker.started(id, "validation", "init"));
        try {
            result = execute(running);
        } catch (Exception e) {
            log.error("Validation {} failed", id, e);
            result = running.failed(UNEXPECTED_ERROR, Instant.now());
        } finally {
            ValidationRecord last = result != null ? result : running.failed(UNEXPECTED_ERROR, Instant.now());
            // a session deleted while running stays deleted
            Optional<ValidationRecord> stored = records.update(id,
                current -> current.status() == ValidationStatus.RUNNING ? last : null);
            if (stored.isEmpty() && records.read(id).isEmpty()) {
                // the section writer runs outside the session's lock and may have recreated the files
                storage.deleteAll(id);
            }
            String type = last.status() == ValidationStatus.FINISHED ? "success" : "fail";
            track(() -> tracker.stopped(id, "validation", type));
        }
    }

    /** Tracking is best effort and never fails a validation. */
    private static void track(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("Could not record a tracking event", e);
        }
    }

    private ValidationRecord execute(ValidationRecord record) throws IOException {
        Path upload = null;
        Path tsv = null;
        Path mapping = null;
        try {
            upload = toTempFile(storage.read(record.id(), SLOT.MZTABFILE, ValidationSessionService.UPLOAD)
                .orElseThrow(() -> new IllegalStateException("The upload of validation " + record.id()
                + " is missing")), ".mztab");
            Optional<String> version;
            Path file;
            if (isJson(upload)) {
                MzTab parsed;
                try (InputStream in = Files.newInputStream(upload)) {
                    parsed = objectMapper.readValue(in, MzTab.class);
                } catch (JsonProcessingException e) {
                    log.info("Validation {}: the JSON upload could not be read: {}", record.id(),
                        e.getOriginalMessage());
                    return notParsed(record, JSON_NOT_READ, jsonDetail(e));
                }
                version = Optional.ofNullable(parsed.getMetadata()).map(m -> m.getMzTabVersion());
                if (version.filter(MzTabVersionDetector::isLegacy).isPresent()) {
                    return record.rejected(version.get(), legacyMessage(), Instant.now());
                }
                // jmzTab-m validates files: write the model as tab separated mzTab-M, as /rest/v2/validate does
                tsv = Files.createTempFile("mztab-validation-", ".mztab");
                try (OutputStreamWriter out = new OutputStreamWriter(Files.newOutputStream(tsv),
                    StandardCharsets.UTF_8)) {
                    new MzTabNonValidatingWriter().write(out, parsed);
                } catch (IOException | RuntimeException e) {
                    // e.g. a model without metadata: the writer reports the mzTab error
                    log.info("Validation {}: the JSON upload could not be written as mzTab-M", record.id(), e);
                    Throwable cause = e.getCause() instanceof MZTabException ? e.getCause() : e;
                    return notParsed(record, JSON_NOT_CONVERTED, cause.getMessage() == null
                        ? cause.getClass().getSimpleName() : cause.getMessage().strip());
                }
                file = tsv;
            } else {
                version = MzTabVersionDetector.detect(upload);
                if (version.filter(MzTabVersionDetector::isLegacy).isPresent()) {
                    return record.rejected(version.get(), legacyMessage(), Instant.now());
                }
                file = upload;
            }
            mapping = mappingFile(record);
            MzTabMValidator.Outcome outcome = validator.validateAndParse(file, record.level(),
                record.maxErrors(), mapping);
            MzTab mzTab = outcome.mzTab();
            SectionsSummary sections = mzTab == null ? null : writeSections(record.id(), mzTab);
            String mzTabVersion = version.orElseGet(() -> mzTab == null || mzTab.getMetadata() == null
                ? null : mzTab.getMetadata().getMzTabVersion());
            return record.finished(outcomeOf(outcome), mzTabVersion, outcome.messages(), sections, Instant.now());
        } finally {
            deleteQuietly(upload);
            deleteQuietly(tsv);
            deleteQuietly(mapping);
        }
    }

    /**
     * The section data is a view of the file; failing to write it must not
     * discard the validation result.
     *
     * @return {@code null} if the sections could not be written
     */
    private SectionsSummary writeSections(UUID id, MzTab mzTab) {
        try {
            return sectionWriter.write(id, mzTab);
        } catch (RuntimeException e) {
            log.error("Validation {}: could not write the sections; the result is kept without them", id, e);
            try {
                storage.delete(id, SLOT.SECTIONS);
            } catch (RuntimeException cleanup) {
                log.warn("Validation {}: could not delete partial sections", id, cleanup);
            }
            return null;
        }
    }

    /** An upload whose first non-blank character is <code>{</code> is an mzTab-M model as JSON. */
    static boolean isJson(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int c;
            while ((c = reader.read()) != -1) {
                if (c == '﻿' || Character.isWhitespace(c)) {
                    continue;
                }
                return c == '{';
            }
            return false;
        } catch (MalformedInputException e) {
            // not UTF-8 text, so not JSON; the mzTab parser reports the file
            return false;
        }
    }

    /** The parser's own message and its position, without the source excerpt or a stack trace. */
    private static String jsonDetail(JsonProcessingException e) {
        String detail = e.getOriginalMessage() == null ? e.getClass().getSimpleName() : e.getOriginalMessage();
        detail = shorten(detail);
        if (e.getLocation() != null && e.getLocation().getLineNr() > 0) {
            detail += " (line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr() + ")";
        }
        return detail;
    }

    private static String shorten(String text) {
        return text.length() > MAX_PARSER_MESSAGE ? text.substring(0, MAX_PARSER_MESSAGE) + "..." : text;
    }

    /** A JSON upload that never reached the validator: one ERROR message. */
    private static ValidationRecord notParsed(ValidationRecord record, String prefix, String detail) {
        ValidationMessage message = new ValidationMessage();
        message.setCategory(ValidationMessage.CategoryEnum.FORMAT);
        message.setCode("");
        message.setLineNumber(-1L);
        message.setMessageType(ValidationMessage.MessageTypeEnum.ERROR);
        message.setMessage(prefix + shorten(detail));
        return record.finished(ValidationOutcome.NOT_PARSED, null, List.of(message), null, Instant.now());
    }

    /** The mapping as a local file, or {@code null} for structural validation only. */
    private Path mappingFile(ValidationRecord record) throws IOException {
        ProfileRef profile = record.validationProfile();
        return switch (profile.kind()) {
            case NONE -> null;
            case CATALOG -> {
                ValidationProfile p = catalog.find(profile.id(), profile.version())
                    .orElseThrow(() -> new IllegalStateException("Validation profile " + profile.id() + " "
                    + profile.version() + " is no longer in the catalog"));
                yield toTempFile(catalog.mappingUrl(p).openStream(), ".xml");
            }
            case CUSTOM -> toTempFile(storage.read(record.id(), SLOT.MAPPINGFILE,
                ValidationSessionService.CUSTOM_MAPPING).orElseThrow(() -> new IllegalStateException(
                "The custom mapping of validation " + record.id() + " is missing")), ".xml");
        };
    }

    static ValidationOutcome outcomeOf(MzTabMValidator.Outcome outcome) {
        if (outcome.mzTab() == null) {
            return ValidationOutcome.NOT_PARSED;
        }
        List<ValidationMessage> messages = outcome.messages();
        if (messages.stream().anyMatch(m -> m.getMessageType() == ValidationMessage.MessageTypeEnum.ERROR)) {
            return ValidationOutcome.FAILED;
        }
        return messages.isEmpty() ? ValidationOutcome.PASSED : ValidationOutcome.PASSED_WITH_NOTES;
    }

    private static ValidationMessage legacyMessage() {
        ValidationMessage message = new ValidationMessage();
        message.setCategory(ValidationMessage.CategoryEnum.FORMAT);
        message.setCode("");
        message.setLineNumber(-1L);
        message.setMessageType(ValidationMessage.MessageTypeEnum.ERROR);
        message.setMessage(MZTAB_1_0);
        return message;
    }

    /**
     * jmzTab-m reads files from paths; session data stays behind
     * {@link StorageService}, so the runner works on a temporary copy.
     */
    private static Path toTempFile(InputStream content, String suffix) throws IOException {
        try (InputStream in = content) {
            Path file = Files.createTempFile("mztab-validation-", suffix);
            try {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                return file;
            } catch (IOException | RuntimeException e) {
                deleteQuietly(file);
                throw e;
            }
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temporary file {}", file, e);
        }
    }
}
