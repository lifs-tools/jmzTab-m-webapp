/*
 * Copyright 2018 Leibniz Institut für Analytische Wissenschaften - ISAS e.V..
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
package org.lifstools.mztab.validator.webapp.domain;

import java.util.List;

/**
 *
 * @author nilshoffmann
 */
public class ValidationStatistics {

    private long errors;
    private long warnings;
    private long infos;
    private boolean noErrorsOrWarnings;

    public ValidationStatistics(List<ValidationResult> validationResults) {
        errors = validationResults.stream().
            filter((t) ->
            {
                return t.getLevel() == ValidationLevel.ERROR;
            }).
            count();
        warnings = validationResults.stream().
            filter((t) ->
            {
                return t.getLevel() == ValidationLevel.WARN;
            }).
            count();
        infos = validationResults.stream().
            filter((t) ->
            {
                return t.getLevel() == ValidationLevel.INFO;
            }).
            count();
        noErrorsOrWarnings = (errors == 0l && warnings == 0l);
    }

    public long getErrors() {
        return errors;
    }

    public void setErrors(long nErrors) {
        this.errors = nErrors;
    }

    public long getWarnings() {
        return warnings;
    }

    public void setWarnings(long nWarnings) {
        this.warnings = nWarnings;
    }

    public long getInfos() {
        return infos;
    }

    public void setInfos(long nInfos) {
        this.infos = nInfos;
    }

    public boolean getNoErrorsOrWarnings() {
        return this.noErrorsOrWarnings;
    }

    public void setNoErrorsOrWarnings(boolean b) {
        this.noErrorsOrWarnings = b;
    }

}
