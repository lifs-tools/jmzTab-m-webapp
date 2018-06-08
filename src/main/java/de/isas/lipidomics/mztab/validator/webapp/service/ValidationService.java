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
package de.isas.lipidomics.mztab.validator.webapp.service;

import de.isas.lipidomics.mztab.validator.webapp.domain.UserSessionFile;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationLevel;
import de.isas.lipidomics.mztab.validator.webapp.domain.ValidationResult;
import de.isas.mztab2.model.ValidationMessage;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
public interface ValidationService {
    static enum MzTabVersion{MZTAB_1_0, MZTAB_2_0};
    List<ValidationMessage> validate(MzTabVersion version, UserSessionFile userSessionFile, int maxErrors, ValidationLevel validationLevel, boolean checkCvMapping);
    
    public Map<String, List<Map<String, String>>> parse(MzTabVersion mzTabVersion,
        UserSessionFile userSessionFile, int maxErrors, ValidationLevel validationLevel);
    
    List<ValidationResult> asValidationResults(List<ValidationMessage> validationMessage);
}
