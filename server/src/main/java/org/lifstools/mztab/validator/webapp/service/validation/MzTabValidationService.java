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

import org.lifstools.mztab.validator.webapp.domain.UserSessionFile;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.service.AnalyticsTracker;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.ValidationService;
import org.lifstools.mztab2.cvmapping.CvParameterLookupService;
import org.lifstools.mztab2.model.ValidationMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
@Service
public class MzTabValidationService implements ValidationService {

    private final StorageService storageService;
    private final AnalyticsTracker tracker;
    private final CvParameterLookupService lookupService;

    @Autowired
    public MzTabValidationService(StorageService storageService,
        AnalyticsTracker tracker, CvParameterLookupService lookupService) {
        this.storageService = storageService;
        this.tracker = tracker;
        this.lookupService = lookupService;
    }

    @Override
    public List<ValidationMessage> validate(
        UserSessionFile userSessionFile, int maxErrors,
        ValidationLevel validationLevel, boolean checkCvMapping,
        UserSessionFile mappingFile) {
        tracker.started(userSessionFile.getSessionId(), "validation", "init");
        Path filepath = storageService.load(userSessionFile,
            StorageService.SLOT.MZTABFILE);

        try {
            List<ValidationMessage> validationResults = new ArrayList<>();
            validationResults.addAll(
                validate(filepath, validationLevel,
                    maxErrors, checkCvMapping, storageService.load(
                        mappingFile, StorageService.SLOT.MAPPINGFILE)));
            tracker.stopped(userSessionFile.getSessionId(), "validation",
                "success");
            return validationResults;
        } catch (IOException ex) {
            if (ex.getCause() instanceof MZTabException) {
                MZTabException mex = (MZTabException) ex.getCause();
                tracker.stopped(userSessionFile.getSessionId(), "validation",
                    "fail");
                return Arrays.asList(mex.getError().
                    toValidationMessage());
            }
            Logger.getLogger(MzTabValidationService.class.getName()).
                log(java.util.logging.Level.SEVERE, null, ex);
        }
        tracker.stopped(userSessionFile.getSessionId(), "validation", "fail");
        return Collections.emptyList();
    }

    private List<ValidationMessage> validate(
        Path filepath,
        ValidationLevel validationLevel, int maxErrors, boolean checkCvMapping,
        Path mappingFile) throws IllegalStateException, IOException {
        if (checkCvMapping) {
            Logger.getLogger(MzTabValidationService.class.getName()).
                log(java.util.logging.Level.INFO,
                    "Running validation on file {0} with validationLevel={1}, maxErrors={2}, and mapping file={3}",
                    new Object[]{filepath, validationLevel,
                        maxErrors, mappingFile});

        } else {
            Logger.getLogger(MzTabValidationService.class.getName()).
                log(java.util.logging.Level.INFO,
                    "Running validation on file {0} with validationLevel={1}, maxErrors={2}",
                    new Object[]{filepath, validationLevel,
                        maxErrors});
        }
        return new MzTabMValidator(lookupService).validate(filepath,
                    validationLevel.
                        name(),
                    maxErrors, checkCvMapping, mappingFile);

    }
}
