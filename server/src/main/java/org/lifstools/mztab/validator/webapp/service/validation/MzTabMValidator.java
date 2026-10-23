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
package org.lifstools.mztab.validator.webapp.service.validation;

import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.MzTab;
import org.lifstools.mztab2.model.ValidationMessage;
import org.lifstools.mztab2.validation.CvMappingValidator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import jakarta.xml.bind.JAXBException;
import org.lifstools.mztab2.io.MzTabFileParser;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
@Slf4j
@Component
public class MzTabMValidator implements WebValidator {

    private final CvParameterLookupService lookupService;

    public MzTabMValidator(CvParameterLookupService lookupService) {
        this.lookupService = lookupService;
    }

    /**
     * The result of {@link #validateAndParse}.
     *
     * @param messages structural and, if a mapping was given, semantic messages
     * @param mzTab the parsed model, or {@code null} if the file could not be
     * parsed into a model
     */
    public record Outcome(List<ValidationMessage> messages, @Nullable MzTab mzTab) {

    }

    @Override
    public List<ValidationMessage> validate(Path filepath,
            String validationLevel, int maxErrors, boolean checkCvMapping, Path validationFile) throws IllegalStateException, IOException {
        ValidationLevel level = ValidationLevel.valueOf(validationLevel.toUpperCase(Locale.ROOT));
        return validateAndParse(filepath, level, maxErrors, checkCvMapping ? validationFile : null).messages();
    }

    /**
     * Parses and validates an mzTab-M file.
     *
     * @param file the mzTab-M file
     * @param level the lowest level of messages to report
     * @param maxErrors the maximum number of messages the parser reports
     * @param mapping a CV mapping file for semantic validation; {@code null}
     * skips semantic validation
     * @return the messages and the parsed model
     */
    public Outcome validateAndParse(Path file, ValidationLevel level, int maxErrors,
            @Nullable Path mapping) throws IOException {
        MzTabFileParser parser = null;
        boolean parsed = false;
        List<ValidationMessage> validationResults = new ArrayList<>();
        try {
            parser = new MzTabFileParser(file.toFile());
            parser.parse(System.out, MZTabErrorType.findLevel(level.name()), maxErrors);
            parsed = true;
        } catch (Exception e) {
            log.error("Caught Exception in MzTabMValidator:", e);
            ValidationMessage vm = new ValidationMessage();
            vm.setCategory(
                    ValidationMessage.CategoryEnum.FORMAT);
            vm.setCode("");
            vm.setLineNumber(-1l);
            vm.setMessageType(
                    ValidationMessage.MessageTypeEnum.ERROR);
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
            return new Outcome(validationResults, null);
        }
        validationResults.addAll(parser.getErrorList().
                convertToValidationMessages());
        MzTab mzTab = parser.getMZTabFile();
        if (mapping != null) {
            validationResults.addAll(validateSemantics(file, mzTab, mapping));
        }
        return new Outcome(validationResults, parsed ? mzTab : null);
    }

    private List<ValidationMessage> validateSemantics(Path file, MzTab mzTab, Path mapping) {
        try {
            CvMappingValidator cvValidator = CvMappingValidator.of(
                    SafeCvMappingLoader.load(mapping),
                    lookupService, true);
            List<ValidationMessage> messages = cvValidator.validate(mzTab);
            return Optional.ofNullable(messages).orElse(Collections.emptyList());
        } catch (Exception iae) {
            log.error("Caught Exception in MzTabMValidator, semantic validation:", iae);
            ValidationMessage vm = new ValidationMessage();
            vm.setCategory(
                    ValidationMessage.CategoryEnum.FORMAT);
            vm.setCode("");
            vm.setLineNumber(-1l);
            vm.setMessageType(
                    ValidationMessage.MessageTypeEnum.ERROR);
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
