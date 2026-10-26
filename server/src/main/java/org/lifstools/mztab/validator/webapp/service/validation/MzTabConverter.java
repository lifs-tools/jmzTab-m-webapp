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
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab2.io.MzTabNonValidatingWriter;
import org.lifstools.mztab2.model.MzTab;
import org.lifstools.mztab2.model.ValidationMessage;
import org.lifstools.mztab2.model.ValidationMessage.MessageTypeEnum;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 * Converts between tab separated mzTab-M and its model, which the REST API
 * reads and writes as JSON. Only errors stop a conversion; warnings and infos
 * are not reported.
 */
@Slf4j
@Component
public class MzTabConverter {

    /** The maximum number of errors a failed conversion reports. */
    static final int MAX_ERRORS = 100;

    static final String NOT_WRITTEN = "The model could not be written as mzTab-M: ";

    private final MzTabMValidator validator;

    public MzTabConverter(MzTabMValidator validator) {
        this.validator = validator;
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
     * @param tsv the file content
     * @return the model, or the errors that prevent reading it
     */
    public Conversion<MzTab> read(String tsv) throws IOException {
        Path file = Files.createTempFile("mztab-conversion-", ".mztab");
        try {
            Files.writeString(file, tsv, StandardCharsets.UTF_8);
            MzTabMValidator.Outcome outcome = validator.validateAndParse(file, ValidationLevel.ERROR,
                MAX_ERRORS, null);
            List<ValidationMessage> errors = errors(outcome.messages());
            return new Conversion<>(errors.isEmpty() ? outcome.mzTab() : null, errors);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Writes a model as tab separated mzTab-M.
     *
     * @param mzTab the model
     * @param validate whether to parse the written file again and fail on its
     * errors
     * @return the file content, or the errors that prevent writing it
     */
    public Conversion<String> write(MzTab mzTab, boolean validate) throws IOException {
        Path file = Files.createTempFile("mztab-conversion-", ".mztab");
        try {
            try (OutputStreamWriter out = new OutputStreamWriter(Files.newOutputStream(file),
                StandardCharsets.UTF_8)) {
                new MzTabNonValidatingWriter().write(out, mzTab);
            } catch (IOException | RuntimeException e) {
                // e.g. a model without metadata: the writer reports the mzTab error
                log.info("A model could not be written as mzTab-M", e);
                Throwable cause = e.getCause() instanceof MZTabException ? e.getCause() : e;
                return new Conversion<>(null, List.of(error(NOT_WRITTEN + (cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage().strip()))));
            }
            if (validate) {
                List<ValidationMessage> errors = errors(validator.validateAndParse(file, ValidationLevel.ERROR,
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

    private static List<ValidationMessage> errors(List<ValidationMessage> messages) {
        return messages.stream().filter(m -> m.getMessageType() == MessageTypeEnum.ERROR).toList();
    }

    private static ValidationMessage error(String message) {
        ValidationMessage vm = new ValidationMessage();
        vm.setCategory(ValidationMessage.CategoryEnum.FORMAT);
        vm.setCode("");
        vm.setLineNumber(-1l);
        vm.setMessageType(MessageTypeEnum.ERROR);
        vm.setMessage(message);
        return vm;
    }
}
