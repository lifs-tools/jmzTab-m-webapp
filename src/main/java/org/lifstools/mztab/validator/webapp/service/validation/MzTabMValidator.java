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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.io.MzTabNonValidatingWriter;
import org.lifstools.mztab2.io.MzTabWriterDefaults;
import org.lifstools.mztab2.model.MzTab;
import org.lifstools.mztab2.model.ValidationMessage;
import org.lifstools.mztab2.validation.CvMappingValidator;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBException;
import org.lifstools.mztab2.io.MzTabFileParser;
import java.io.PrintWriter;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import uk.ac.ebi.pride.jmztab2.model.MZTabConstants;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorList;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
@Slf4j
public class MzTabMValidator implements WebValidator {

    private final CvParameterLookupService lookupService;

    public MzTabMValidator(CvParameterLookupService lookupService) {
        this.lookupService = lookupService;
    }

    @Override
    public List<ValidationMessage> validate(Path filepath,
            String validationLevel, int maxErrors, boolean checkCvMapping, Path validationFile) throws IllegalStateException, IOException {
        MzTabFileParser parser = null;
        List<ValidationMessage> validationResults = new ArrayList<>();
        try {
            parser = new MzTabFileParser(filepath.toFile());
            MZTabErrorList errorList = parser.parse(
                    System.out, MZTabErrorType.findLevel(validationLevel), maxErrors);
        } catch (Exception e) {
            log.error("Caught Exception in IsasValidator:", e);
            ValidationMessage vm = new ValidationMessage();
            vm.setCategory(
                    ValidationMessage.CategoryEnum.FORMAT);
            vm.setCode("");
            vm.setLineNumber(-1l);
            vm.setMessageType(
                    ValidationMessage.MessageTypeEnum.ERROR);
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            StringBuilder message = new StringBuilder();
            message.append("Basic validation failed for file '").append(filepath.getFileName()).append("'\n");
            if (e.getMessage() != null) {
                message.append(" with message: '").append(e.getMessage()).append("'\n");
            }
            message.append("Please check your file's structure and inspect further validation messages!\n");
            vm.setMessage(message.toString());
            validationResults.add(vm);
        } finally {
            if (parser != null) {
                validationResults.addAll(parser.getErrorList().
                        convertToValidationMessages());
                if (checkCvMapping) {
                    try {
                        CvMappingValidator cvValidator = CvMappingValidator.of(
                                validationFile.toFile(),
                                lookupService, checkCvMapping);
                        List<ValidationMessage> messages = cvValidator.validate(parser.
                                getMZTabFile());
                        validationResults.addAll(Optional.ofNullable(messages).orElse(Collections.emptyList()));
                    } catch (Exception iae) {
                        log.error("Caught Exception in IsasValidator, semantic validation:", iae);
                        ValidationMessage vm = new ValidationMessage();
                        vm.setCategory(
                                ValidationMessage.CategoryEnum.FORMAT);
                        vm.setCode("");
                        vm.setLineNumber(-1l);
                        vm.setMessageType(
                                ValidationMessage.MessageTypeEnum.ERROR);
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        iae.printStackTrace(pw);
                        StringBuilder message = new StringBuilder();
                        message.append("Semantic validation failed for file '").append(filepath.getFileName()).append("'\n");
                        if (iae.getMessage() != null) {
                            message.append(" with message: '").append(iae.getMessage()).append("'\n");
                        }
                        message.append("Please check your file's structure and inspect further validation messages!\n");
                        vm.setMessage(message.toString());
                        validationResults.add(vm);
                    }
                }
            }
        }
        return validationResults;
    }

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
                MzTabWriterDefaults writerDefaults = new MzTabWriterDefaults();
                CsvMapper mapper = writerDefaults.metadataMapper();
                CsvSchema schema = writerDefaults.metaDataSchema(mapper);
                if (mzTabFile.getMetadata().
                        getMzTabVersion() == null) {
                    //set default version if not set
                    mzTabFile.getMetadata().
                            mzTabVersion(MZTabConstants.VERSION_MZTAB_M);
                }
                StringWriter writer = new StringWriter();
                try {
                    mapper.writer(schema).
                            writeValue(writer, mzTabFile.getMetadata());
                } catch (JsonProcessingException ex) {
                    Logger.getLogger(MzTabNonValidatingWriter.class.getName()).
                            log(Level.SEVERE, null, ex);
                }
                Map<String, List<Map<String, String>>> mzTabLines = new LinkedHashMap<>();
                String[] metaDataLines = writer.toString().
                        split(MZTabConstants.NEW_LINE);
                List<Map<String, String>> metaData = new ArrayList<>();
                int lineNumber = 1;
                for (int i = 0; i < metaDataLines.length; i++) {
                    String[] metaDataLine = metaDataLines[i].split(
                            MZTabConstants.TAB_STRING);
                    Map<String, String> lineMap = new LinkedHashMap<>();
                    lineMap.put("LINE_NUMBER", lineNumber + "");
                    lineMap.put("PREFIX", metaDataLine[0]);
                    lineMap.put("KEY", metaDataLine[1]);
                    lineMap.put("VALUE", metaDataLine[2]);
                    metaData.add(lineMap);
                    lineNumber++;
                }
                mzTabLines.put("META", metaData);

                mapper = writerDefaults.smallMoleculeSummaryMapper();
                try {
                    schema = writerDefaults.
                            smallMoleculeSummarySchema(mapper, mzTabFile);
                    writer = new StringWriter();
                    mapper.writer(schema).
                            writeValue(writer, mzTabFile.getSmallMoleculeSummary());
                } catch (JsonProcessingException ex) {
                    Logger.getLogger(MzTabNonValidatingWriter.class.getName()).
                            log(Level.SEVERE, null, ex);
                } catch (MZTabException ex) {
                    Logger.getLogger(MzTabMValidator.class.getName()).
                            log(Level.SEVERE, null, ex);
                    throw new IOException(ex);
                }

                String[] summaryDataLines = writer.toString().
                        split(MZTabConstants.NEW_LINE);
                String[] summaryHeader = summaryDataLines[0].split(
                        MZTabConstants.TAB_STRING);
                List<Map<String, String>> summaryData = new ArrayList<>();
                //due to the header
                lineNumber++;
                for (int i = 1; i < summaryDataLines.length; i++) {
                    String[] dataLine = summaryDataLines[i].split(
                            MZTabConstants.TAB_STRING);
                    Map<String, String> lineMap = new LinkedHashMap<>();
                    lineMap.put("LINE_NUMBER", lineNumber + "");
                    for (int j = 0; j < summaryHeader.length; j++) {
                        lineMap.put(summaryHeader[j], dataLine[j]);
                    }
                    summaryData.add(lineMap);
                    lineNumber++;
                }
                mzTabLines.put("SUMMARY", summaryData);

                mapper = writerDefaults.smallMoleculeFeatureMapper();
                try {
                    schema = writerDefaults.
                            smallMoleculeFeatureSchema(mapper, mzTabFile);
                    writer = new StringWriter();
                    mapper.writer(schema).
                            writeValue(writer, mzTabFile.getSmallMoleculeFeature());
                } catch (JsonProcessingException ex) {
                    Logger.getLogger(MzTabNonValidatingWriter.class.getName()).
                            log(Level.SEVERE, null, ex);
                } catch (MZTabException ex) {
                    Logger.getLogger(MzTabMValidator.class.getName()).
                            log(Level.SEVERE, null, ex);
                    throw new IOException(ex);
                }
                String[] featureDataLines = writer.toString().
                        split(MZTabConstants.NEW_LINE);
                String[] featureHeader = featureDataLines[0].split(
                        MZTabConstants.TAB_STRING);
                List<Map<String, String>> featureData = new ArrayList<>();
                //due to the header
                lineNumber++;
                for (int i = 1; i < featureDataLines.length; i++) {
                    String[] dataLine = featureDataLines[i].split(
                            MZTabConstants.TAB_STRING);
                    Map<String, String> lineMap = new LinkedHashMap<>();
                    lineMap.put("LINE_NUMBER", lineNumber + "");
                    for (int j = 0; j < featureHeader.length; j++) {
                        lineMap.put(featureHeader[j], dataLine[j]);
                    }
                    featureData.add(lineMap);
                    lineNumber++;
                }
                mzTabLines.put("FEATURE", featureData);

                mapper = writerDefaults.smallMoleculeEvidenceMapper();
                try {
                    schema = writerDefaults.smallMoleculeEvidenceSchema(mapper,
                            mzTabFile);
                    writer = new StringWriter();
                    mapper.writer(schema).
                            writeValue(writer, mzTabFile.getSmallMoleculeEvidence());
                } catch (JsonProcessingException ex) {
                    Logger.getLogger(MzTabNonValidatingWriter.class.getName()).
                            log(Level.SEVERE, null, ex);
                } catch (MZTabException ex) {
                    Logger.getLogger(MzTabMValidator.class.getName()).
                            log(Level.SEVERE, null, ex);
                    throw new IOException(ex);
                }
                String[] evidenceDataLines = writer.toString().
                        split(MZTabConstants.NEW_LINE);
                String[] evidenceHeader = evidenceDataLines[0].split(
                        MZTabConstants.TAB_STRING);
                List<Map<String, String>> evidenceData = new ArrayList<>();
                //due to the header
                lineNumber++;
                for (int i = 1; i < evidenceDataLines.length; i++) {
                    String[] dataLine = evidenceDataLines[i].split(
                            MZTabConstants.TAB_STRING);
                    Map<String, String> lineMap = new LinkedHashMap<>();
                    lineMap.put("LINE_NUMBER", lineNumber + "");
                    for (int j = 0; j < evidenceHeader.length; j++) {
                        lineMap.put(evidenceHeader[j], dataLine[j]);
                    }
                    evidenceData.add(lineMap);
                    lineNumber++;
                }
                mzTabLines.put("EVIDENCE", evidenceData);

                return mzTabLines;
            }
            return Collections.emptyMap();
        }
    }
}
