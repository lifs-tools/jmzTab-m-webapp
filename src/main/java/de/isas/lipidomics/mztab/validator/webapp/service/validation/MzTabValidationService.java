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

import de.isas.lipidomics.mztab.validator.webapp.domain.ToolResult;
import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationLevel;
import static de.isas.lipidomics.mztab.validator.webapp.domain.ValidationLevel.ERROR;
import static de.isas.lipidomics.mztab.validator.webapp.domain.ValidationLevel.WARN;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationResult;
import de.isas.lipidomics.mztab.validator.webapp.service.AnalyticsTracker;
import de.isas.lipidomics.mztab.validator.webapp.service.StorageService;
import de.isas.lipidomics.mztab.validator.webapp.service.ToolResultService;
import de.isas.lipidomics.mztab.validator.webapp.service.ValidationService;
import de.isas.mztab2.cvmapping.CvParameterLookupService;
import de.isas.mztab2.model.ValidationMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import uk.ac.ebi.pride.jmztab2.utils.errors.MZTabException;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
@Service
public class MzTabValidationService implements ValidationService {

    private final StorageService storageService;
    private final AnalyticsTracker tracker;
    private final ToolResultService resultService;
    private final CvParameterLookupService lookupService;

    @Autowired
    public MzTabValidationService(StorageService storageService,
        AnalyticsTracker tracker, ToolResultService resultService,
        CvParameterLookupService lookupService) {
        this.storageService = storageService;
        this.tracker = tracker;
        this.resultService = resultService;
        this.lookupService = lookupService;
    }

    @Override
    public List<ValidationMessage> validate(MzTabVersion mzTabVersion,
        UserSessionFile userSessionFile, int maxErrors,
        ValidationLevel validationLevel, boolean checkCvMapping,
        UserSessionFile mappingFile) {
        tracker.started(userSessionFile.getSessionId(), "validation", "init");
        Path filepath = storageService.load(userSessionFile,
            StorageService.SLOT.MZTABFILE);

        try {
            List<ValidationMessage> validationResults = new ArrayList<>();
            validationResults.addAll(
                validate(mzTabVersion, filepath, validationLevel,
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

    @Override
    public Map<String, List<Map<String, String>>> parse(
        MzTabVersion mzTabVersion,
        UserSessionFile userSessionFile, int maxErrors,
        ValidationLevel validationLevel) {
        tracker.started(userSessionFile.getSessionId(), "parse", "init");
        Path filepath = storageService.load(userSessionFile,
            StorageService.SLOT.MZTABFILE);

        try {
            Map<String, List<Map<String, String>>> lines = parse(mzTabVersion,
                filepath, validationLevel,
                maxErrors);
            tracker.stopped(userSessionFile.getSessionId(), "parse", "success");
            return lines;
        } catch (IOException | RuntimeException ex) {
            Logger.getLogger(MzTabValidationService.class.getName()).
                log(java.util.logging.Level.SEVERE, null, ex);
            tracker.stopped(userSessionFile.getSessionId(), "parse", "fail");
        }
        return Collections.emptyMap();
    }

    private Map<String, List<Map<String, String>>> parse(
        MzTabVersion mzTabVersion,
        Path filepath,
        ValidationLevel validationLevel, int maxErrors) throws IllegalStateException, IOException {
        switch (mzTabVersion) {
            case MZTAB_1_0:
                return new EbiValidator().parse(filepath, validationLevel.
                    name(),
                    maxErrors);
            case MZTAB_2_0:
                return new IsasValidator(lookupService).parse(filepath,
                    validationLevel.
                        name(),
                    maxErrors);
            default:
                throw new IllegalStateException(
                    "Unsupported mzTab version: " + mzTabVersion.toString());
        }
    }

    private List<ValidationMessage> validate(MzTabVersion mzTabVersion,
        Path filepath,
        ValidationLevel validationLevel, int maxErrors, boolean checkCvMapping,
        Path mappingFile) throws IllegalStateException, IOException {
        if (checkCvMapping) {
            Logger.getLogger(MzTabValidationService.class.getName()).
                log(java.util.logging.Level.INFO,
                    "Running validation on file {0} for mzTab version={1}, validationLevel={2}, maxErrors={3}, and mapping file={4}",
                    new Object[]{filepath, mzTabVersion, validationLevel,
                        maxErrors, mappingFile});

        } else {
            Logger.getLogger(MzTabValidationService.class.getName()).
                log(java.util.logging.Level.INFO,
                    "Running validation on file {0} for mzTab version={1}, validationLevel={2}, maxErrors={3}",
                    new Object[]{filepath, mzTabVersion, validationLevel,
                        maxErrors});
        }
        switch (mzTabVersion) {
            case MZTAB_1_0:
                return new EbiValidator().validate(filepath, validationLevel.
                    name(),
                    maxErrors, checkCvMapping, mappingFile);
            case MZTAB_2_0:
                return new IsasValidator(lookupService).validate(filepath,
                    validationLevel.
                        name(),
                    maxErrors, checkCvMapping, mappingFile);
            default:
                throw new IllegalStateException(
                    "Unsupported mzTab version: " + mzTabVersion.toString());
        }

    }

    @Override
    public List<ValidationResult> asValidationResults(
        List<ValidationMessage> validationMessage) {
        return validationMessage.stream().
            map((message) ->
            {
                ValidationLevel level = ValidationLevel.valueOf(message.
                    getMessageType().
                    getValue().
                    toUpperCase());
                return new ValidationResult(message.getLineNumber(), message.
                    getCategory().
                    name(), level,
                    message.getMessage(), message.getCode());
            }).
            collect(Collectors.toList());
    }

    @Override
    public List<ValidationResult> filterByLevel(
        List<ValidationResult> validationResults, ValidationLevel level) {
        return validationResults.stream().
            filter((vr) ->
            {
                switch (level) {
                    case ERROR:
                        if (vr.getLevel() == ERROR) {
                            return true;
                        } else {
                            return false;
                        }
                    case WARN:
                        if (vr.getLevel() == WARN || vr.getLevel() == ERROR) {
                            return true;
                        } else {
                            return false;
                        }
                    default:
                        return true;
                }
            }).
            sorted((o1,
                o2) ->
            {
                int i = Long.compare(o1.getLineNumber(), o2.getLineNumber());
                if (i == 0) {
                    if (o1.getLevel() == o2.getLevel()) {
                        return 0;
                    } else if (o1.getLevel() == ValidationLevel.ERROR && (o2.
                        getLevel() == ValidationLevel.WARN || o2.getLevel() == ValidationLevel.INFO)) {
                        return -1;
                    }
                    return 1;
                } else {
                    return i;
                }
            }).
            collect(Collectors.toList());
    }

    @Override
    public ToolResult getStatus(UUID userSessionId) {
        return resultService.getOrCreateResultFor(userSessionId);
    }

    @Async("toolThreadPoolTaskExecutor")
    @Override
    public CompletableFuture<ToolResult> runValidation(MzTabVersion mzTabVersion,
        UserSessionFile userSessionFile, int maxErrors,
        ValidationLevel validationLevel, boolean checkCvMapping,
        UserSessionFile mappingFile) {
        UUID userSessionId = userSessionFile.getSessionId();
        ToolResult status = resultService.getOrCreateResultFor(
            userSessionId);
        //return immediately, if we have finished already
        if (status.getStatus() == Status.FINISHED || status.getStatus() == Status.FAILED) {
            return CompletableFuture.completedFuture(status);
        }
        if (status.getStatus() == Status.UNINITIALIZED) {
            tracker.started(userSessionId, "validation", "init");
            status.setStatus(Status.PREPARING);
            Path filepath = storageService.load(userSessionFile,
                StorageService.SLOT.MZTABFILE);
            status.setStatus(Status.STARTED);
            try {
                List<ValidationMessage> validationResults = new ArrayList<>();
                status.setStatus(Status.RUNNING);
                Path mappingFilePath = storageService.load(mappingFile, StorageService.SLOT.MAPPINGFILE);
                if (!mappingFilePath.toFile().exists()) {
                    throw new IOException("Semantic validation file "+mappingFilePath+" does not exist!");
                }
                List<ValidationMessage> messages = validate(mzTabVersion, filepath, validationLevel,
                        maxErrors, checkCvMapping, mappingFilePath);
                validationResults.addAll(messages);
                tracker.stopped(userSessionFile.getSessionId(), "validation",
                    "success");
                status.setMessages(validationResults);
                status.setStatus(Status.FINISHED);
                resultService.addResultFor(userSessionId, status);
                return CompletableFuture.completedFuture(status);
            } catch (IOException | RuntimeException ex) {
                tracker.
                    stopped(userSessionFile.getSessionId(), "validation",
                        "fail");
                Logger.getLogger(MzTabValidationService.class.getName()).
                    log(Level.SEVERE, null, ex);
                status.setException(ex);
                status.setStatus(Status.FAILED);
                if (ex.getCause() instanceof MZTabException) {
                    MZTabException mex = (MZTabException) ex.getCause();
                    status.setMessages(Arrays.asList(mex.getError().
                        toValidationMessage()));
                } else {
                    ValidationMessage vm = new ValidationMessage();
                    vm.setCategory(
                            ValidationMessage.CategoryEnum.CROSS_CHECK);
                    vm.setCode("");
                    vm.setLineNumber(-1l);
                    vm.setMessageType(
                            ValidationMessage.MessageTypeEnum.ERROR);
                    vm.setMessage(ex.getMessage());
                    status.setMessages(Arrays.asList(vm));
                }
                resultService.addResultFor(userSessionId, status);
                tracker.
                    stopped(userSessionFile.getSessionId(), "validation", "fail");
                return CompletableFuture.completedFuture(status);
            }
        } else {
            return CompletableFuture.completedFuture(status);
        }
    }
}
