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
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import uk.ac.ebi.pride.jmztab2.model.MZTabConstants;
import uk.ac.ebi.pride.jmztab2.utils.MZTabFileParser;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabError;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorList;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorType;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
public class IsasValidator implements WebValidator {

    @Override
    public List<ValidationMessage> validate(Path filepath,
        String validationLevel, int maxErrors) throws IllegalStateException, IOException {
        MZTabFileParser parser = null;
        List<ValidationMessage> validationResults = Collections.emptyList();
        try {
            parser = new MZTabFileParser(filepath.toFile());
            parser.parse(
                System.out, MZTabErrorType.findLevel(validationLevel), maxErrors);
        } finally {
            if (parser != null) {
                MZTabErrorList errorList = parser.getErrorList();
                validationResults = new ArrayList<>(
                    errorList.size());
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
                            throw new IllegalStateException("State " + error.
                                getType().
                                getLevel() + " is not handled in switch/case statement!");
                    }
                    ValidationMessage vr = new ValidationMessage().lineNumber(
                        Long.valueOf(error.getLineNumber())).
                        messageType(level).
                        message(error.getMessage()).
                        code(error.toString());
                    Logger.getLogger(MzTabValidationService.class.getName()).
                        info(vr.toString());
                    validationResults.add(vr);
                }
            }
            return validationResults;
        }
    }

    @Override
    public Map<String, List<List<String>>> parse(Path filepath,
        String validationLevel, int maxErrors) throws IOException {
        MZTabFileParser parser = new MZTabFileParser(filepath.toFile());
        parser.parse(
            System.out, MZTabErrorType.findLevel(validationLevel), maxErrors);
        MzTab mzTabFile = parser.getMZTabFile();
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
        Map<String, List<List<String>>> mzTabLines = new LinkedHashMap<>();
        String[] metaDataLines = writer.toString().
            split(MZTabConstants.NEW_LINE);
        List<List<String>> metaData = new ArrayList<>();
        for (int i = 0; i < metaDataLines.length; i++) {
            String[] metaDataLine = metaDataLines[i].split(
                MZTabConstants.TAB_STRING);
            metaData.add(Arrays.asList("" + (i + 1), metaDataLine[0],
                metaDataLine[1], metaDataLine[2]));
        }
        mzTabLines.put("METADATA", metaData);
        return mzTabLines;
    }
}
