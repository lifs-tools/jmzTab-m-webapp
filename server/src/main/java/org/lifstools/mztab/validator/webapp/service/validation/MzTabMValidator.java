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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.xml.bind.JAXBException;
import org.lifstools.mztab2.io.MzTabFileParser;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab.validator.webapp.sections.Section;
import org.lifstools.mztab.validator.webapp.sections.SectionWriter;
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
            log.error("Caught Exception in IsasValidator:", e);
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
            log.error("Caught Exception in IsasValidator, semantic validation:", iae);
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

    /**
     * Thin adapter over {@link SectionWriter#render} for the result page,
     * which Task 15 removes.
     */
    @Override
    public Map<String, List<Map<String, String>>> parse(Path filepath,
            String validationLevel, int maxErrors) throws IOException {
        MzTabFileParser parser = new MzTabFileParser(filepath.toFile());
        try {
            parser.parse(
                    System.out, MZTabErrorType.findLevel(validationLevel), maxErrors);
        } finally {
            MzTab mzTabFile = parser.getMZTabFile();
            if (mzTabFile != null) {
                SectionWriter.Rendered rendered = SectionWriter.render(mzTabFile);
                Map<String, List<Map<String, String>>> mzTabLines = new LinkedHashMap<>();
                List<Map<String, String>> metaData = new ArrayList<>();
                int lineNumber = 1;
                for (List<String> cells : rendered.metadata()) {
                    Map<String, String> lineMap = new LinkedHashMap<>();
                    lineMap.put("LINE_NUMBER", lineNumber + "");
                    lineMap.put("PREFIX", cells.get(0));
                    lineMap.put("KEY", cells.get(1));
                    lineMap.put("VALUE", cells.get(2));
                    metaData.add(lineMap);
                    lineNumber++;
                }
                mzTabLines.put("META", metaData);
                lineNumber = addTable(mzTabLines, "SUMMARY", rendered.tables().get(Section.SML), lineNumber);
                lineNumber = addTable(mzTabLines, "FEATURE", rendered.tables().get(Section.SMF), lineNumber);
                addTable(mzTabLines, "EVIDENCE", rendered.tables().get(Section.SME), lineNumber);
                return mzTabLines;
            }
            return Collections.emptyMap();
        }
    }

    private static int addTable(Map<String, List<Map<String, String>>> target, String name,
            List<List<String>> table, int lineNumber) {
        List<Map<String, String>> data = new ArrayList<>();
        if (table != null) {
            List<String> header = table.get(0);
            // due to the header
            lineNumber++;
            for (List<String> row : table.subList(1, table.size())) {
                Map<String, String> lineMap = new LinkedHashMap<>();
                lineMap.put("LINE_NUMBER", lineNumber + "");
                for (int j = 0; j < header.size(); j++) {
                    lineMap.put(header.get(j), row.get(j));
                }
                data.add(lineMap);
                lineNumber++;
            }
        }
        target.put(name, data);
        return lineNumber;
    }
}
