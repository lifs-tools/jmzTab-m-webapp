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
import de.isas.mztab2.model.ValidationMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    @Autowired
    public MzTabValidationService(StorageService storageService,
        AnalyticsTracker tracker, ToolResultService resultService) {
        this.storageService = storageService;
        this.tracker = tracker;
        this.resultService = resultService;
    }

    @Override
    public List<ValidationMessage> validate(MzTabVersion mzTabVersion,
        UserSessionFile userSessionFile, int maxErrors,
        ValidationLevel validationLevel, boolean checkCvMapping) {
        tracker.started(userSessionFile.getSessionId(), "validation", "init");
        Path filepath = storageService.load(userSessionFile);

        try {
            List<ValidationMessage> validationResults = new ArrayList<>();
            validationResults.addAll(
                validate(mzTabVersion, filepath, validationLevel,
                    maxErrors, checkCvMapping));
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
        Path filepath = storageService.load(userSessionFile);

        try {
            Map<String, List<Map<String, String>>> lines = parse(mzTabVersion,
                filepath, validationLevel,
                maxErrors);
            tracker.stopped(userSessionFile.getSessionId(), "parse", "success");
            return lines;
        } catch (IOException ex) {
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
                return new IsasValidator().parse(filepath, validationLevel.
                    name(),
                    maxErrors);
            default:
                throw new IllegalStateException(
                    "Unsupported mzTab version: " + mzTabVersion.toString());
        }
    }

    private List<ValidationMessage> validate(MzTabVersion mzTabVersion,
        Path filepath,
        ValidationLevel validationLevel, int maxErrors, boolean checkCvMapping) throws IllegalStateException, IOException {
        switch (mzTabVersion) {
            case MZTAB_1_0:
                return new EbiValidator().validate(filepath, validationLevel.
                    name(),
                    maxErrors, checkCvMapping);
            case MZTAB_2_0:
                return new IsasValidator().validate(filepath, validationLevel.
                    name(),
                    maxErrors, checkCvMapping);
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
        ValidationLevel validationLevel, boolean checkCvMapping) {
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
            Path filepath = storageService.load(userSessionFile);
            status.setStatus(Status.STARTED);
            try {
                List<ValidationMessage> validationResults = new ArrayList<>();
                status.setStatus(Status.RUNNING);
                validationResults.addAll(
                    validate(mzTabVersion, filepath, validationLevel,
                        maxErrors, checkCvMapping));
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
                status.setException(ex);
                status.setStatus(Status.FAILED);
                if (ex.getCause() instanceof MZTabException) {
                    MZTabException mex = (MZTabException) ex.getCause();
                    status.setMessages(Arrays.asList(mex.getError().
                        toValidationMessage()));
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
