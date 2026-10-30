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
package org.lifstools.mztab.validator.webapp.service.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.EngineErrorCode;
import org.lifstools.mztab.validator.engine.api.ValidationEvent;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.engine.DeclaredVersions;
import org.lifstools.mztab.validator.webapp.engine.EngineBusyException;
import org.lifstools.mztab.validator.webapp.engine.EngineClient;
import org.lifstools.mztab.validator.webapp.engine.EngineMessages;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry.RouteDecision;
import org.lifstools.mztab.validator.webapp.engine.EngineRejection;
import org.lifstools.mztab.validator.webapp.engine.EngineRequestException;
import org.lifstools.mztab.validator.webapp.engine.EngineResultReader;
import org.lifstools.mztab.validator.webapp.engine.EngineUnavailableException;
import org.lifstools.mztab.validator.webapp.engine.EnginesProperties;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Validates and converts a file on the request thread of {@code /rest/v2/validate}, {@code /validatePlain},
 * {@code /convert} and {@code /convertPlain}, through the engine of the file's declared version line, as
 * {@code ValidationRunner} does for the validations with a session. A file of an mzTab 1.x version or of a line
 * without an engine on this instance is answered with one message and reaches no engine.
 *
 * <p>An engine that cannot be reached or whose answer breaks off throws {@link EngineUnavailableException}, a busy
 * one {@link EngineBusyException}; the REST advice answers both with 503. The engine refusing a file as too large
 * throws {@link MaxUploadSizeExceededException} (413), and a conversion body that is not mzTab-M JSON
 * {@link IllegalArgumentException} (400).
 */
@Slf4j
@Component
public class SynchronousEngineService {

    /** What a conversion produced. */
    public sealed interface Conversion {

        /**
         * @param file the converted file, a temporary file the caller streams and deletes
         */
        record Converted(Path file) implements Conversion {

        }

        /**
         * @param messages why the file was not converted, at least one error
         */
        record Rejected(List<ValidationMessage> messages) implements Conversion {

        }
    }

    private final EngineRegistry registry;
    private final EngineResultReader reader;
    private final ValidationProfileCatalog catalog;
    private final EnginesProperties engines;

    public SynchronousEngineService(EngineRegistry registry, EngineResultReader reader,
        ValidationProfileCatalog catalog, EnginesProperties engines) {
        this.registry = registry;
        this.reader = reader;
        this.catalog = catalog;
        this.engines = engines;
    }

    /**
     * Validates a file.
     *
     * @param file the file, tab separated or JSON
     * @param declared its declared {@code mzTab-version} ({@link DeclaredVersions})
     * @param semantic whether to validate against the default profile of the file's line as well; a line without
     * a default profile is validated structurally, with an INFO message saying so
     * @return the messages of the engine; one error if the file reaches no engine
     */
    public List<ValidationMessage> validate(Path file, Optional<String> declared, ValidationLevel level,
        int maxErrors, boolean semantic) throws IOException {
        RouteDecision decision = registry.route(declared);
        Optional<String> rejection = EngineMessages.rejection(decision, engines.unavailableHintUrl());
        if (rejection.isPresent()) {
            return List.of(ValidationMessage.error(rejection.get()));
        }
        RouteDecision.Routed routed = (RouteDecision.Routed) decision;
        List<ValidationMessage> messages = new ArrayList<>();
        Path mapping = null;
        try {
            if (semantic) {
                Optional<ValidationProfile> profile = catalog.defaultFor(routed.line());
                if (profile.isPresent()) {
                    mapping = toTempFile(catalog.mappingUrl(profile.get()).openStream(), ".xml");
                } else {
                    messages.add(ValidationMessage.info(EngineMessages.noDefaultProfile(routed.line())));
                }
            }
            try {
                ValidationEvent.Outcome outcome = routed.engine().validate(file, mapping, level, maxErrors,
                    reader::readOutcome);
                if (outcome.messages() != null) {
                    messages.addAll(outcome.messages());
                }
            } catch (EngineRejection e) {
                messages.addAll(e.messages());
            } catch (EngineRequestException e) {
                messages.add(refused(routed.line(), e));
            }
            return messages;
        } finally {
            deleteQuietly(mapping);
        }
    }

    /** Converts a tab separated file to the JSON representation of its line's model. */
    public Conversion tsvToJson(Path tsv) throws IOException {
        return convert(DeclaredVersions.ofTsv(tsv), tsv, ".json",
            (engine, in, target) -> engine.tsvToJson(in, target));
    }

    /**
     * Converts the JSON representation to a tab separated file.
     *
     * @param validate whether the engine parses the written file again and rejects it if it has errors
     */
    public Conversion jsonToTsv(Path json, boolean validate) throws IOException {
        return convert(DeclaredVersions.ofJson(json), json, ".mztab",
            (engine, in, target) -> engine.jsonToTsv(in, validate, target));
    }

    @FunctionalInterface
    private interface EngineConversion {

        void run(EngineClient engine, InputStream in, Path target);
    }

    private Conversion convert(Optional<String> declared, Path file, String suffix, EngineConversion conversion)
        throws IOException {
        RouteDecision decision = registry.route(declared);
        Optional<String> rejection = EngineMessages.rejection(decision, engines.unavailableHintUrl());
        if (rejection.isPresent()) {
            return new Conversion.Rejected(List.of(ValidationMessage.error(rejection.get())));
        }
        RouteDecision.Routed routed = (RouteDecision.Routed) decision;
        Path target = Files.createTempFile("mztab-conversion-", suffix);
        boolean converted = false;
        try (InputStream in = Files.newInputStream(file)) {
            conversion.run(routed.engine(), in, target);
            converted = true;
            return new Conversion.Converted(target);
        } catch (EngineRejection e) {
            return new Conversion.Rejected(e.messages());
        } catch (EngineRequestException e) {
            if (e.error().code() == EngineErrorCode.NOT_JSON) {
                throw new IllegalArgumentException(e.error().message(), e);
            }
            return new Conversion.Rejected(List.of(refused(routed.line(), e)));
        } finally {
            if (!converted) {
                deleteQuietly(target);
            }
        }
    }

    /**
     * The engine refused the request: too large is a 413, as if the webapp had refused it; anything else is
     * reported as one error, as {@code ValidationRunner} does.
     */
    private static ValidationMessage refused(String line, EngineRequestException e) {
        if (e.error().code() == EngineErrorCode.FILE_TOO_LARGE) {
            throw new MaxUploadSizeExceededException(-1, e);
        }
        log.warn("The engine for mzTab-M {} refused a file: {} {}", line, e.error().code(), e.error().message());
        return ValidationMessage.error(e.error().message());
    }

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

    /** Deletes a temporary file; a failure is logged, not thrown. */
    public static void deleteQuietly(@Nullable Path file) {
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
