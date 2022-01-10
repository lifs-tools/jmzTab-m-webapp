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
package org.lifstools.mztab.validator.webapp.domain;

import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import java.util.Objects;

/**
 *
 * @author Nils Hoffmann &lt;nils.hoffmann@isas.de&gt;
 */
public class ValidationResult {

    private final Long lineNumber;
    private final String category;
    private final ValidationLevel level;
    private final String message;
    private final String ruleId;
    private final String styleClass;

    public ValidationResult(Long lineNumber, String category,
        ValidationLevel level, String message, String ruleId) {
        this.lineNumber = lineNumber;
        this.category = category;
        this.level = level;
        this.message = message;
        this.ruleId = ruleId;
        switch (level) {
            case ERROR:
                this.styleClass = "table-danger";
                break;
            case WARN:
                this.styleClass = "table-warning";
                break;
            default:
                this.styleClass = "";
        }
    }

    public Long getLineNumber() {
        return lineNumber;
    }

    public ValidationLevel getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getStyleClass() {
        return styleClass;
    }

    public String getCategory() {
        return category;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 41 * hash + Objects.hashCode(this.lineNumber);
        hash = 41 * hash + Objects.hashCode(this.category);
        hash = 41 * hash + Objects.hashCode(this.level);
        hash = 41 * hash + Objects.hashCode(this.message);
        hash = 41 * hash + Objects.hashCode(this.ruleId);
        hash = 41 * hash + Objects.hashCode(this.styleClass);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final ValidationResult other = (ValidationResult) obj;
        if (!Objects.equals(this.category, other.category)) {
            return false;
        }
        if (!Objects.equals(this.message, other.message)) {
            return false;
        }
        if (!Objects.equals(this.ruleId, other.ruleId)) {
            return false;
        }
        if (!Objects.equals(this.styleClass, other.styleClass)) {
            return false;
        }
        if (!Objects.equals(this.lineNumber, other.lineNumber)) {
            return false;
        }
        if (this.level != other.level) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ValidationResult{" + "lineNumber=" + lineNumber + ", category=" + category + ", level=" + level + ", message=" + message + ", ruleId=" + ruleId + ", styleClass=" + styleClass + '}';
    }

}
