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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import de.isas.mztab2.io.MzTabNonValidatingWriter;
import de.isas.mztab2.io.MzTabWriterDefaults;
import de.isas.mztab2.model.MzTab;
import de.isas.mztab2.model.ValidationMessage;
import de.isas.mztab2.validation.CvMappingValidator;
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
import uk.ac.ebi.pride.jmztab2.model.MZTabConstants;
import uk.ac.ebi.pride.jmztab2.utils.MZTabFileParser;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorOverflowException;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
public class IsasValidator implements WebValidator {

    @Override
    public List<ValidationMessage> validate(Path filepath,
        String validationLevel, int maxErrors, boolean checkCvMapping) throws IllegalStateException, IOException {
        MZTabFileParser parser = null;
        List<ValidationMessage> validationResults = Collections.emptyList();
        try {
            parser = new MZTabFileParser(filepath.toFile());
            parser.parse(
                System.out, MZTabErrorType.findLevel(validationLevel), maxErrors);
        } catch (MZTabErrorOverflowException ex) {
            Logger.getLogger(IsasValidator.class.getName()).
                            log(Level.SEVERE, null, ex);
        } finally {
            if (parser != null) {
                validationResults = parser.getErrorList().
                    convertToValidationMessages();
                if (checkCvMapping) {
                    try {
                        CvMappingValidator cvValidator = CvMappingValidator.of(
                            CvMappingValidator.class.getResource(
                                "/mappings/mzTab-M-mapping.xml"), checkCvMapping);
                        validationResults.addAll(cvValidator.validate(parser.
                            getMZTabFile()));
                    } catch (JAXBException ex) {
                        Logger.getLogger(IsasValidator.class.getName()).
                            log(Level.SEVERE, null, ex);
                        throw new IOException(ex);
                    }
                }
            }
        }
        return validationResults;
    }

    @Override
    public Map<String, List<Map<String, String>>> parse(Path filepath,
        String validationLevel, int maxErrors) throws IOException {
        MZTabFileParser parser = new MZTabFileParser(filepath.toFile());
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
                    Logger.getLogger(IsasValidator.class.getName()).
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
                    Logger.getLogger(IsasValidator.class.getName()).
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
                    Logger.getLogger(IsasValidator.class.getName()).
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
