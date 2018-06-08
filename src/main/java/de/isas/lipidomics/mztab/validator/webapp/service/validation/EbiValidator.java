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
package de.isas.lipidomics.mztab.validator.webapp.service.validation;

import de.isas.mztab2.model.ValidationMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Logger;
import uk.ac.ebi.pride.jmztab.utils.MZTabFileParser;
import uk.ac.ebi.pride.jmztab.utils.errors.MZTabError;
import uk.ac.ebi.pride.jmztab.utils.errors.MZTabErrorList;
import uk.ac.ebi.pride.jmztab.utils.errors.MZTabErrorType;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
public class EbiValidator implements WebValidator {

    @Override
    public List<ValidationMessage> validate(Path filepath,
        String validationLevel, int maxErrors, boolean checkCvMapping) throws IllegalStateException, IOException {
        SortedSet<ValidationMessage> results = new TreeSet<>((vm1,
            vm2) ->
        {
            int lineNumber = Long.compare(vm1.getLineNumber(), vm2.
                getLineNumber());
            if (lineNumber == 0) {
                int messageType = vm1.getMessageType().
                    compareTo(vm2.getMessageType());
                if (messageType == 0) {
                    int code = vm1.getCode().
                        compareTo(vm2.getCode());
                    return code;
                } else {
                    return messageType;
                }
            }
            return lineNumber;
        });
        MZTabErrorType.Level level = MZTabErrorType.findLevel(validationLevel);
        switch (level) {
            case Info:
                applyParserForLevel(results, filepath,
                    MZTabErrorType.Level.Info.name(), maxErrors);
                applyParserForLevel(results, filepath,
                    MZTabErrorType.Level.Warn.name(), maxErrors);
                applyParserForLevel(results, filepath,
                    MZTabErrorType.Level.Error.name(), maxErrors);
                break;
            case Warn:
                applyParserForLevel(results, filepath,
                    MZTabErrorType.Level.Warn.name(), maxErrors);
                applyParserForLevel(results, filepath,
                    MZTabErrorType.Level.Error.name(), maxErrors);
                break;
            case Error:
                applyParserForLevel(results, filepath,
                    MZTabErrorType.Level.Error.name(), maxErrors);
                break;
            default:
                throw new IllegalStateException(
                    "State '" + level + "' is not handled in switch/case statement!");
        }

        List<ValidationMessage> validationResults = new ArrayList<>(results.
            size());
        validationResults.addAll(results);
        return validationResults.subList(0, Math.min(
            validationResults.size(), maxErrors));
    }

    private void applyParserForLevel(SortedSet<ValidationMessage> results,
        Path filepath,
        String validationLevel, int maxErrors) throws IllegalStateException, IOException {
        MZTabFileParser parser = new MZTabFileParser(filepath.toFile(),
            System.out, MZTabErrorType.findLevel(validationLevel), maxErrors);
        MZTabErrorList errorList = parser.getErrorList();
        for (MZTabError error : errorList.getErrorList()) {
            ValidationMessage.MessageTypeEnum level = ValidationMessage.MessageTypeEnum.INFO;
            switch (error.getType().
                getLevel()) {
                case Error:
                    level = ValidationMessage.MessageTypeEnum.ERROR;
                    break;
                case Info:
                    level = ValidationMessage.MessageTypeEnum.INFO;
                    break;
                case Warn:
                    level = ValidationMessage.MessageTypeEnum.WARN;
                    break;
                default:
                    throw new IllegalStateException("State '" + error.getType().
                        getLevel() + "' is not handled in switch/case statement!");
            }
            ValidationMessage vr = new ValidationMessage().lineNumber(Long.
                valueOf(error.getLineNumber())).
                messageType(level).
                message(error.getMessage()).
                code(error.toString());
            Logger.getLogger(MzTabValidationService.class.getName()).
                info(vr.toString());
            results.add(vr);
        }
    }

    @Override
    public Map<String, List<Map<String,String>>> parse(Path filepath, String validationLevel,
        int maxErrors) throws IOException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}
