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
package org.lifstools.mztab.validator.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.engine.api.ValidationMessage.MessageType;
import org.lifstools.mztab2.io.MzTabNonValidatingWriter;
import org.lifstools.mztab2.model.MzTab;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 * Converts between tab separated mzTab-M and its JSON representation. Only
 * errors stop a conversion; warnings and infos are not reported. Request
 * bodies go to a temporary file, never into memory as a whole; their size is
 * capped by the caller. Ported from the webapp's {@code MzTabConverter}.
 */
@Slf4j
@Component
public class EngineConverter {

    /** The maximum number of errors a failed conversion reports. */
    static final int MAX_ERRORS = 100;

    static final String NOT_WRITTEN = "The model could not be written as mzTab-M: ";

    static final String NOT_JSON = "The request body is not mzTab-M JSON: ";

    private static final Consumer<MzTab> NO_CHECK = mzTab -> {
    };

    private static final byte[] VERSION_LINE = "MTD\tmzTab-version\t".getBytes(StandardCharsets.UTF_8);

    private final EngineValidator validator;

    private final ObjectMapper objectMapper;

    public EngineConverter(EngineValidator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /**
     * The result of a conversion.
     *
     * @param value the converted file or model, or {@code null} if the
     * conversion failed
     * @param errors why the conversion failed; empty if it succeeded
     */
    public record Conversion<T>(@Nullable T value, List<ValidationMessage> errors) {

        public boolean succeeded() {
            return value != null && errors.isEmpty();
        }
    }

    /**
     * Parses tab separated mzTab-M into its model.
     *
     * @param tsv the file content, UTF-8
     * @return the model, or the errors that prevent reading it
     */
    public Conversion<MzTab> read(InputStream tsv) throws IOException {
        Path file = receive(tsv, null);
        try {
            return read(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Parses a tab separated mzTab-M file into its model.
     *
     * @param file the file, UTF-8
     * @return the model, or the errors that prevent reading it
     */
    public Conversion<MzTab> read(Path file) throws IOException {
        return read(file, NO_CHECK);
    }

    /**
     * Parses a tab separated mzTab-M file into its model.
     *
     * @param file the file, UTF-8
     * @param check runs on the parsed model, if there is one, before anything
     * of it is returned; it refuses the model by throwing
     * @return the model, or the errors that prevent reading it
     */
    public Conversion<MzTab> read(Path file, Consumer<MzTab> check) throws IOException {
        EngineValidator.Outcome outcome = validator.validate(file, ValidationLevel.ERROR, MAX_ERRORS, null);
        if (outcome.mzTab() != null) {
            check.accept(outcome.mzTab());
        }
        List<ValidationMessage> errors = errors(outcome.messages());
        return new Conversion<>(errors.isEmpty() ? outcome.mzTab() : null, errors);
    }

    /**
     * Writes the JSON representation of mzTab-M as tab separated mzTab-M.
     *
     * @param json the JSON representation
     * @param validate whether to parse the written file again and fail on its
     * errors
     * @return the file content, or the errors that prevent writing it
     * @throws IllegalArgumentException if the JSON is not mzTab-M
     */
    public Conversion<String> write(InputStream json, boolean validate) throws IOException {
        Path in = receive(json, null);
        try {
            return write(in, validate);
        } finally {
            Files.deleteIfExists(in);
        }
    }

    /**
     * Writes a file with the JSON representation of mzTab-M as tab separated
     * mzTab-M.
     *
     * @param json the file with the JSON representation
     * @param validate whether to parse the written file again and fail on its
     * errors
     * @return the file content, or the errors that prevent writing it
     * @throws IllegalArgumentException if the JSON is not mzTab-M
     */
    public Conversion<String> write(Path json, boolean validate) throws IOException {
        return write(json, validate, NO_CHECK);
    }

    /**
     * Writes a file with the JSON representation of mzTab-M as tab separated
     * mzTab-M.
     *
     * @param json the file with the JSON representation
     * @param validate whether to parse the written file again and fail on its
     * errors
     * @param check runs on the model read from the JSON before it is written;
     * it refuses the model by throwing
     * @return the file content, or the errors that prevent writing it
     * @throws IllegalArgumentException if the JSON is not mzTab-M
     */
    public Conversion<String> write(Path json, boolean validate, Consumer<MzTab> check) throws IOException {
        MzTab mzTab;
        try {
            mzTab = objectMapper.readValue(json.toFile(), MzTab.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(NOT_JSON + e.getOriginalMessage(), e);
        }
        check.accept(mzTab);
        return write(mzTab, validate);
    }

    private Conversion<String> write(MzTab mzTab, boolean validate) throws IOException {
        Path file = Files.createTempFile("mztab-conversion-", ".mztab");
        try {
            Optional<String> failure = writeTsv(mzTab, file);
            if (failure.isPresent()) {
                return new Conversion<>(null, List.of(ValidationMessage.error(NOT_WRITTEN + failure.get())));
            }
            if (validate) {
                List<ValidationMessage> errors = errors(validator.validate(file, ValidationLevel.ERROR,
                    MAX_ERRORS, null).messages());
                if (!errors.isEmpty()) {
                    return new Conversion<>(null, errors);
                }
            }
            return new Conversion<>(Files.readString(file, StandardCharsets.UTF_8), List.of());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Writes a model as tab separated mzTab-M with jmzTab-m's writer. A model
     * without {@code mzTab-version} is written without one: jmzTab-m's writer
     * would put in its default version, and the file would be validated as if
     * it declared it instead of reporting the missing version.
     *
     * @param mzTab the model; it is not changed
     * @param file the file to write
     * @return empty if the model was written, otherwise why not, e.g. the
     * mzTab error of a model without metadata
     */
    static Optional<String> writeTsv(MzTab mzTab, Path file) throws IOException {
        boolean versionMissing = mzTab.getMetadata() != null && mzTab.getMetadata().getMzTabVersion() == null;
        try (OutputStreamWriter out = new OutputStreamWriter(Files.newOutputStream(file),
            StandardCharsets.UTF_8)) {
            new MzTabNonValidatingWriter().write(out, mzTab);
        } catch (IOException | RuntimeException e) {
            // e.g. a model without metadata: the writer reports the mzTab error
            log.info("A model could not be written as mzTab-M", e);
            Throwable cause = e.getCause() instanceof MZTabException ? e.getCause() : e;
            return Optional.of(cause.getMessage() == null ? cause.getClass().getSimpleName()
                : cause.getMessage().strip());
        } finally {
            if (versionMissing) {
                // jmzTab-m's metadata serializer sets its default version on the model
                mzTab.getMetadata().setMzTabVersion(null);
            }
        }
        if (versionMissing) {
            dropVersionLine(file);
        }
        return Optional.empty();
    }

    /**
     * Removes the first {@code MTD mzTab-version} line, which jmzTab-m's
     * writer puts first; the rest of the file is copied as it is.
     */
    private static void dropVersionLine(Path file) throws IOException {
        Path filtered = Files.createTempFile(file.toAbsolutePath().getParent(), "mztab-conversion-", ".mztab");
        try {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file));
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(filtered))) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                boolean dropped = false;
                int b;
                while (!dropped && (b = in.read()) != -1) {
                    line.write(b);
                    if (b == '\n') {
                        dropped = isVersionLine(line);
                        if (!dropped) {
                            line.writeTo(out);
                        }
                        line.reset();
                    }
                }
                if (!dropped && !isVersionLine(line)) {
                    // the last line, without a line end
                    line.writeTo(out);
                }
                in.transferTo(out);
            }
            Files.move(filtered, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(filtered);
        }
    }

    private static boolean isVersionLine(ByteArrayOutputStream line) {
        byte[] bytes = line.toByteArray();
        return bytes.length >= VERSION_LINE.length
            && Arrays.equals(bytes, 0, VERSION_LINE.length, VERSION_LINE, 0, VERSION_LINE.length);
    }

    /**
     * Copies a request body to a new temporary file.
     *
     * @param dir the directory of the file; {@code null} for the default
     * temporary directory
     * @throws IOException or a runtime exception if the body cannot be read,
     * e.g. because it is over the upload limit; the file is deleted
     */
    static Path receive(InputStream body, @Nullable Path dir) throws IOException {
        Path file = dir == null ? Files.createTempFile("mztab-conversion-", ".upload")
            : Files.createTempFile(dir, "mztab-conversion-", ".upload");
        boolean kept = false;
        try {
            Files.copy(body, file, StandardCopyOption.REPLACE_EXISTING);
            kept = true;
            return file;
        } finally {
            if (!kept) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static List<ValidationMessage> errors(List<ValidationMessage> messages) {
        return messages.stream().filter(m -> m.getMessageType() == MessageType.ERROR).toList();
    }
}
