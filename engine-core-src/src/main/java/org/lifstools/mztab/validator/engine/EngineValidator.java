/*
 * Copyright 2017 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.io.MzTabFileParser;
import org.lifstools.mztab2.model.MzTab;
import org.lifstools.mztab2.validation.CvMappingValidator;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

/**
 * Structural and semantic validation with the engine's jmzTab-m line. Ported
 * from the webapp's {@code MzTabMValidator}; the messages are converted to the
 * engine contract's {@link ValidationMessage}.
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
@Slf4j
@Component
public class EngineValidator {

    private final CvParameterLookupService lookupService;

    /** Converts jmzTab-m's messages; a field a newer jmzTab-m adds is dropped, not an error. */
    private final ObjectMapper messageMapper;

    public EngineValidator(CvParameterLookupService lookupService, ObjectMapper objectMapper) {
        this.lookupService = lookupService;
        this.messageMapper = objectMapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * The result of {@link #validate}.
     *
     * @param messages structural and, if a mapping was given, semantic messages
     * @param mzTab the parsed model, or {@code null} if the file could not be
     * parsed into a model
     */
    public record Outcome(List<ValidationMessage> messages, @Nullable MzTab mzTab) {

    }

    /**
     * Parses and validates an mzTab-M file.
     *
     * @param file the mzTab-M file
     * @param level the lowest level of messages to report
     * @param maxErrors the maximum number of messages the parser reports, and
     * the maximum number of semantic messages
     * @param mapping a CV mapping file for semantic validation; {@code null}
     * skips semantic validation
     * @return the messages and the parsed model
     */
    public Outcome validate(Path file, ValidationLevel level, int maxErrors, @Nullable Path mapping)
        throws IOException {
        MzTabFileParser parser = null;
        boolean parsed = false;
        List<org.lifstools.mztab2.model.ValidationMessage> validationResults = new ArrayList<>();
        try {
            parser = new MzTabFileParser(file.toFile());
            parser.parse(System.out, MZTabErrorType.findLevel(level.name()), maxErrors);
            parsed = true;
        } catch (Exception e) {
            log.error("Caught Exception in EngineValidator:", e);
            org.lifstools.mztab2.model.ValidationMessage vm = new org.lifstools.mztab2.model.ValidationMessage();
            vm.setCategory(
                org.lifstools.mztab2.model.ValidationMessage.CategoryEnum.FORMAT);
            vm.setCode("");
            vm.setLineNumber(-1l);
            vm.setMessageType(
                org.lifstools.mztab2.model.ValidationMessage.MessageTypeEnum.ERROR);
            StringBuilder message = new StringBuilder();
            message.append("Basic validation failed for file '").append(file.getFileName()).append("'\n");
            if (e.getMessage() != null) {
                message.append(" with message: '").append(e.getMessage()).append("'\n");
            }
            message.append("Please check your file's structure and inspect further validation messages!\n");
            vm.setMessage(message.toString());
            validationResults.add(vm);
        }
        if (parser == null) {
            return new Outcome(converted(validationResults), null);
        }
        validationResults.addAll(parser.getErrorList().
            convertToValidationMessages());
        MzTab mzTab = parser.getMZTabFile();
        if (mapping != null && mzTab == null) {
            // jmzTab-m builds no model when the file has errors; there is nothing to check
            validationResults.add(note("Semantic validation was skipped: the errors above prevent reading "
                + "the file's content. Fix them and validate again."));
        } else if (mapping != null) {
            validationResults.addAll(capped(validateSemantics(file, mzTab, mapping), maxErrors));
        }
        return new Outcome(converted(validationResults), parsed ? mzTab : null);
    }

    private List<ValidationMessage> converted(List<org.lifstools.mztab2.model.ValidationMessage> messages) {
        List<ValidationMessage> result = new ArrayList<>(messages.size());
        for (org.lifstools.mztab2.model.ValidationMessage message : messages) {
            result.add(messageMapper.convertValue(message, ValidationMessage.class));
        }
        return result;
    }

    /**
     * The first {@code maxErrors} semantic messages; if more were dropped, an
     * info message saying how many follows them. A CV mapping rule may report
     * every row of a large table.
     */
    private static List<org.lifstools.mztab2.model.ValidationMessage> capped(
        List<org.lifstools.mztab2.model.ValidationMessage> messages, int maxErrors) {
        int omitted = messages.size() - maxErrors;
        if (omitted <= 0) {
            return messages;
        }
        List<org.lifstools.mztab2.model.ValidationMessage> kept = new ArrayList<>(messages.subList(0, maxErrors));
        kept.add(note(omitted + " further semantic validation messages were omitted. "
            + "Raise the maximum number of messages to see them."));
        return kept;
    }

    /** An info message of the validator itself about semantic validation. */
    private static org.lifstools.mztab2.model.ValidationMessage note(String message) {
        org.lifstools.mztab2.model.ValidationMessage vm = new org.lifstools.mztab2.model.ValidationMessage();
        vm.setCategory(org.lifstools.mztab2.model.ValidationMessage.CategoryEnum.CROSS_CHECK);
        vm.setCode("");
        vm.setLineNumber(-1l);
        vm.setMessageType(org.lifstools.mztab2.model.ValidationMessage.MessageTypeEnum.INFO);
        vm.setMessage(message);
        return vm;
    }

    List<org.lifstools.mztab2.model.ValidationMessage> validateSemantics(Path file, MzTab mzTab, Path mapping) {
        try {
            CvMappingValidator cvValidator = CvMappingValidator.of(
                SafeCvMappingLoader.load(mapping),
                lookupService, true);
            List<org.lifstools.mztab2.model.ValidationMessage> messages = cvValidator.validate(mzTab);
            return Optional.ofNullable(messages).orElse(Collections.emptyList());
        } catch (Exception iae) {
            log.error("Caught Exception in EngineValidator, semantic validation:", iae);
            org.lifstools.mztab2.model.ValidationMessage vm = new org.lifstools.mztab2.model.ValidationMessage();
            vm.setCategory(
                org.lifstools.mztab2.model.ValidationMessage.CategoryEnum.FORMAT);
            vm.setCode("");
            vm.setLineNumber(-1l);
            vm.setMessageType(
                org.lifstools.mztab2.model.ValidationMessage.MessageTypeEnum.ERROR);
            StringBuilder message = new StringBuilder();
            message.append("Semantic validation failed for file '").append(file.getFileName()).append("'\n");
            if (iae.getMessage() != null) {
                message.append(" with message: '").append(iae.getMessage()).append("'\n");
            }
            message.append("Please check your file's structure and inspect further validation messages!\n");
            vm.setMessage(message.toString());
            return List.of(vm);
        }
    }
}
