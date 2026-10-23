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
package org.lifstools.mztab.validator.webapp.service;

import org.lifstools.mztab.validator.webapp.domain.ToolResult;
import org.lifstools.mztab.validator.webapp.domain.UserSessionFile;
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab.validator.webapp.domain.ValidationResult;
import org.lifstools.mztab2.model.ValidationMessage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
public interface ValidationService {
    public static enum Status {
        UNINITIALIZED, PREPARING, STARTED, RUNNING, FINISHED, FAILED
    };

    @Async
    CompletableFuture<ToolResult> runValidation(MzTabVersion mzTabVersion,
        UserSessionFile userSessionFile, int maxErrors,
        ValidationLevel validationLevel, boolean checkCvMapping, UserSessionFile validationFile);

    ToolResult getStatus(UUID userSessionId);
    
    static enum MzTabVersion{MZTAB_1_0, MZTAB_2_0};
    List<ValidationMessage> validate(MzTabVersion version, UserSessionFile userSessionFile, int maxErrors, ValidationLevel validationLevel, boolean checkCvMapping, UserSessionFile validationFile);
    
    public Map<String, List<Map<String, String>>> parse(MzTabVersion mzTabVersion,
        UserSessionFile userSessionFile, int maxErrors, ValidationLevel validationLevel);
    
    List<ValidationResult> asValidationResults(List<ValidationMessage> validationMessage);
    
    List<ValidationResult> filterByLevel(List<ValidationResult> validationResults, ValidationLevel level);
}
