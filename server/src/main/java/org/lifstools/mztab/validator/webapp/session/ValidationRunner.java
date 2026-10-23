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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.webapp.sections.SectionWriter;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.validation.MzTabMValidator;
import org.lifstools.mztab.validator.webapp.service.validation.MzTabVersionDetector;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.lifstools.mztab2.model.MzTab;
import org.lifstools.mztab2.model.ValidationMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs a queued validation: version check, structural and semantic
 * validation with jmzTab-m, section data, and the persisted result.
 */
@Slf4j
@Component
public class ValidationRunner {

    static final String MZTAB_1_0 = "This file declares mzTab-version 1.0. mzTab 1.0 is no longer supported; "
        + "the validator checks mzTab-M 2.0 files.";
    static final String UNEXPECTED_ERROR = "The validation could not be completed because of an unexpected "
        + "server error. Please try again later.";

    private final ValidationRecordStore records;
    private final StorageService storage;
    private final MzTabMValidator validator;
    private final ValidationProfileCatalog catalog;
    private final SectionWriter sectionWriter;

    public ValidationRunner(ValidationRecordStore records, StorageService storage, MzTabMValidator validator,
        ValidationProfileCatalog catalog, SectionWriter sectionWriter) {
        this.records = records;
        this.storage = storage;
        this.validator = validator;
        this.catalog = catalog;
        this.sectionWriter = sectionWriter;
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
        try {
            result = execute(running);
        } catch (Exception e) {
            log.error("Validation {} failed", id, e);
            result = running.failed(UNEXPECTED_ERROR, Instant.now());
        } finally {
            ValidationRecord last = result != null ? result : running.failed(UNEXPECTED_ERROR, Instant.now());
            // a session deleted while running stays deleted
            records.update(id, current -> current.status() == ValidationStatus.RUNNING ? last : null);
        }
    }

    private ValidationRecord execute(ValidationRecord record) throws IOException {
        Path upload = null;
        Path mapping = null;
        try {
            upload = toTempFile(storage.read(record.id(), SLOT.MZTABFILE, ValidationSessionService.UPLOAD)
                .orElseThrow(() -> new IllegalStateException("The upload of validation " + record.id()
                + " is missing")), ".mztab");
            Optional<String> version = MzTabVersionDetector.detect(upload);
            if (version.filter(MzTabVersionDetector::isLegacy).isPresent()) {
                return record.rejected(version.get(), legacyMessage(), Instant.now());
            }
            mapping = mappingFile(record);
            MzTabMValidator.Outcome outcome = validator.validateAndParse(upload, record.level(),
                record.maxErrors(), mapping);
            MzTab mzTab = outcome.mzTab();
            SectionsSummary sections = mzTab == null ? null : sectionWriter.write(record.id(), mzTab);
            String mzTabVersion = version.orElseGet(() -> mzTab == null || mzTab.getMetadata() == null
                ? null : mzTab.getMetadata().getMzTabVersion());
            return record.finished(outcomeOf(outcome), mzTabVersion, outcome.messages(), sections, Instant.now());
        } finally {
            deleteQuietly(upload);
            deleteQuietly(mapping);
        }
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
