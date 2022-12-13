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

import org.lifstools.mztab2.model.ValidationMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Logger;
import uk.ac.ebi.pride.jmztab.model.MZTabFile;
import uk.ac.ebi.pride.jmztab.utils.MZTabFileParser;
import uk.ac.ebi.pride.jmztab.utils.errors.MZTabError;
import uk.ac.ebi.pride.jmztab.utils.errors.MZTabErrorList;
import uk.ac.ebi.pride.jmztab.utils.errors.MZTabErrorType;
import uk.ac.ebi.pride.jmztab.utils.errors.MZTabException;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
public class EbiValidator implements WebValidator {

    @Override
    public List<ValidationMessage> validate(Path filepath,
        String validationLevel, int maxErrors, boolean checkCvMapping, Path validationFile) throws IllegalStateException, IOException {
        SortedSet<ValidationMessage> results = new TreeSet<>((vm1,
            vm2) ->
        {
            if (vm1.getLineNumber() == -1) {
                return 1;
            } else if (vm2.getLineNumber() == -1) {
                return -1;
            }
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
        try {
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
                        throw new IllegalStateException("State '" + error.
                            getType().
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
        } catch (IOException ex) {
            if (ex.getCause() instanceof MZTabException) {
                MZTabException mex = (MZTabException) ex.getCause();
                ValidationMessage.MessageTypeEnum level;
                switch (mex.getError().
                    getType().
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
                        throw new IllegalStateException("State '" + mex.
                            getError().
                            getType().
                            getLevel() + "' is not handled in switch/case statement!");
                }
                ValidationMessage vr = new ValidationMessage().lineNumber(Long.
                    valueOf(mex.getError().
                        getLineNumber())).
                    messageType(level).
                    message(mex.getError().
                        getMessage()).
                    code(mex.getError().
                        toString());
                Logger.getLogger(MzTabValidationService.class.getName()).
                    info(vr.toString());
                results.add(vr);
            }
        }
    }

    @Override
    public Map<String, List<Map<String, String>>> parse(Path filepath,
        String validationLevel,
        int maxErrors) throws IOException {
        MZTabFileParser parser = new MZTabFileParser(filepath.toFile(),
            System.out, MZTabErrorType.findLevel(validationLevel), maxErrors);
        MZTabFile mzTabFile = parser.getMZTabFile();
        int lineNumber = 1;
        Map<String, List<Map<String, String>>> mzTabLines = new LinkedHashMap<>();
//        if (mzTabFile == null) {
        return mzTabLines;
//        }
//        if (mzTabFile.getMetadata() != null) {
//            try (StringWriter writer = new StringWriter()) {
//                writer.write(mzTabFile.getMetadata().
//                    toString());
//                String[] metaDataLines = writer.toString().
//                    split(MZTabConstants.NEW_LINE);
//                List<Map<String, String>> metaData = new ArrayList<>();
//                for (int i = 0; i < metaDataLines.length; i++) {
//                    String[] metaDataLine = metaDataLines[i].split(
//                        MZTabConstants.TAB_STRING);
//                    Map<String, String> lineMap = new LinkedHashMap<>();
//                    lineMap.put("LINE_NUMBER", lineNumber + "");
//                    lineMap.put("PREFIX", metaDataLine[0]);
//                    lineMap.put("KEY", metaDataLine[1]);
//                    lineMap.put("VALUE", metaDataLine[2]);
//                    metaData.add(lineMap);
//                    lineNumber++;
//                }
//                mzTabLines.put("META", metaData);
//            }
//        }
//
//        if (mzTabFile.getSmallMolecules() != null && mzTabFile.
//            getSmallMoleculeColumnFactory() != null) {
//            try (StringWriter writer = new StringWriter()) {
//                mzTabFile.getSmallMoleculeColumnFactory().
//                    getHeaderList().
//                    forEach((header) ->
//                    {
//                        writer.write(header + MZTabConstants.TAB_STRING);
//                    });
//                writer.write(MZTabConstants.NEW_LINE);
//                mzTabFile.getSmallMolecules().
//                    forEach((sm) ->
//                    {
//                        writer.write(sm.toString() + MZTabConstants.NEW_LINE);
//                    });
//
//                String[] summaryDataLines = writer.toString().
//                    split(MZTabConstants.NEW_LINE);
//                String[] summaryHeader = summaryDataLines[0].split(
//                    MZTabConstants.TAB_STRING);
//                List<Map<String, String>> summaryData = new ArrayList<>();
//                //due to the header
//                lineNumber++;
//                for (int i = 1; i < summaryDataLines.length; i++) {
//                    String[] dataLine = summaryDataLines[i].split(
//                        MZTabConstants.TAB_STRING);
//                    Map<String, String> lineMap = new LinkedHashMap<>();
//                    lineMap.put("LINE_NUMBER", lineNumber + "");
//                    for (int j = 0; j < summaryHeader.length; j++) {
//                        lineMap.put(summaryHeader[j], dataLine[j]);
//                    }
//                    summaryData.add(lineMap);
//                    lineNumber++;
//                }
//                mzTabLines.put("SUMMARY", summaryData);
//            }
//        }
//
//        if (mzTabFile.getProteins() != null && mzTabFile.
//            getProteinColumnFactory() != null) {
//            try (StringWriter writer = new StringWriter()) {
//                mzTabFile.getProteinColumnFactory().
//                    getHeaderList().
//                    forEach((header) ->
//                    {
//                        writer.write(header + MZTabConstants.TAB_STRING);
//                    });
//                writer.write(MZTabConstants.NEW_LINE);
//                mzTabFile.getProteins().
//                    forEach((sm) ->
//                    {
//                        writer.write(sm.toString() + MZTabConstants.NEW_LINE);
//                    });
//
//                String[] summaryDataLines = writer.toString().
//                    split(MZTabConstants.NEW_LINE);
//                String[] summaryHeader = summaryDataLines[0].split(
//                    MZTabConstants.TAB_STRING);
//                List<Map<String, String>> summaryData = new ArrayList<>();
//                //due to the header
//                lineNumber++;
//                for (int i = 1; i < summaryDataLines.length; i++) {
//                    String[] dataLine = summaryDataLines[i].split(
//                        MZTabConstants.TAB_STRING);
//                    Map<String, String> lineMap = new LinkedHashMap<>();
//                    lineMap.put("LINE_NUMBER", lineNumber + "");
//                    for (int j = 0; j < summaryHeader.length; j++) {
//                        lineMap.put(summaryHeader[j], dataLine[j]);
//                    }
//                    summaryData.add(lineMap);
//                    lineNumber++;
//                }
//                mzTabLines.put("PROTEINS", summaryData);
//            }
//        }
//
//        if (mzTabFile.getPeptides() != null && mzTabFile.
//            getPeptideColumnFactory() != null) {
//            try (StringWriter writer = new StringWriter()) {
//                mzTabFile.getPeptideColumnFactory().
//                    getHeaderList().
//                    forEach((header) ->
//                    {
//                        writer.write(header + MZTabConstants.TAB_STRING);
//                    });
//                writer.write(MZTabConstants.NEW_LINE);
//                mzTabFile.getPeptides().
//                    forEach((sm) ->
//                    {
//                        writer.write(sm.toString() + MZTabConstants.NEW_LINE);
//                    });
//                String[] summaryDataLines = writer.toString().
//                    split(MZTabConstants.NEW_LINE);
//                String[] summaryHeader = summaryDataLines[0].split(
//                    MZTabConstants.TAB_STRING);
//                List<Map<String, String>> summaryData = new ArrayList<>();
//                //due to the header
//                lineNumber++;
//                for (int i = 1; i < summaryDataLines.length; i++) {
//                    String[] dataLine = summaryDataLines[i].split(
//                        MZTabConstants.TAB_STRING);
//                    Map<String, String> lineMap = new LinkedHashMap<>();
//                    lineMap.put("LINE_NUMBER", lineNumber + "");
//                    for (int j = 0; j < summaryHeader.length; j++) {
//                        lineMap.put(summaryHeader[j], dataLine[j]);
//                    }
//                    summaryData.add(lineMap);
//                    lineNumber++;
//                }
//                mzTabLines.put("PEPTIDES", summaryData);
//            }
//        }
//
//        if (mzTabFile.getPSMs() != null && mzTabFile.getPsmColumnFactory() != null) {
//            try (StringWriter writer = new StringWriter()) {
//                mzTabFile.getPsmColumnFactory().
//                    getHeaderList().
//                    forEach((header) ->
//                    {
//                        writer.write(header + MZTabConstants.TAB_STRING);
//                    });
//                writer.write(MZTabConstants.NEW_LINE);
//                mzTabFile.getPSMs().
//                    forEach((sm) ->
//                    {
//                        writer.write(sm.toString() + MZTabConstants.NEW_LINE);
//                    });
//                String[] summaryDataLines = writer.toString().
//                    split(MZTabConstants.NEW_LINE);
//                String[] summaryHeader = summaryDataLines[0].split(
//                    MZTabConstants.TAB_STRING);
//                List<Map<String, String>> summaryData = new ArrayList<>();
//                //due to the header
//                lineNumber++;
//                for (int i = 1; i < summaryDataLines.length; i++) {
//                    String[] dataLine = summaryDataLines[i].split(
//                        MZTabConstants.TAB_STRING);
//                    Map<String, String> lineMap = new LinkedHashMap<>();
//                    lineMap.put("LINE_NUMBER", lineNumber + "");
//                    for (int j = 0; j < summaryHeader.length; j++) {
//                        lineMap.put(summaryHeader[j], dataLine[j]);
//                    }
//                    summaryData.add(lineMap);
//                    lineNumber++;
//                }
//                mzTabLines.put("PSMS", summaryData);
//            }
//        }
//
//        return mzTabLines;
    }
}
