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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.web.multipart.MultipartFile;

/**
 *
 * @author Nils Hoffmann nils.hoffmann@cebitec.uni-bielefeld.de;
 */
public class ValidationForm {

    private MultipartFile file = null;
    private MultipartFile mappingFile = null;
    private Integer maxErrors = 100;
    private ValidationLevel level = ValidationLevel.INFO;
    private Boolean checkCvMapping = false;
    private final List<ValidationLevel> allLevels = Arrays.asList(
        ValidationLevel.values());

    public ValidationForm() {
        this(null, null, 100,
            ValidationLevel.INFO, false);
    }

    public ValidationForm(MultipartFile file, MultipartFile mappingFile,
        Integer maxErrors,
        ValidationLevel level, Boolean checkCvMapping) {
        this.file = file;
        this.mappingFile = mappingFile;
        this.maxErrors = maxErrors;
        this.level = level;
        this.checkCvMapping = checkCvMapping;
    }

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }

    public MultipartFile getMappingFile() {
        return mappingFile;
    }

    public void setMappingFile(MultipartFile mappingFile) {
        this.mappingFile = mappingFile;
    }

    public Integer getMaxErrors() {
        return maxErrors;
    }

    public void setMaxErrors(Integer maxErrors) {
        this.maxErrors = maxErrors;
    }

    public ValidationLevel getLevel() {
        return level;
    }

    public void setLevel(ValidationLevel level) {
        this.level = level;
    }

    public List<ValidationLevel> getAllLevels() {
        return this.allLevels;
    }

    public Boolean getCheckCvMapping() {
        return this.checkCvMapping;
    }

    public void setCheckCvMapping(Boolean checkCvMapping) {
        this.checkCvMapping = checkCvMapping;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 59 * hash + Objects.hashCode(this.file);
        hash = 59 * hash + Objects.hashCode(this.mappingFile);
        hash = 59 * hash + Objects.hashCode(this.maxErrors);
        hash = 59 * hash + Objects.hashCode(this.level);
        hash = 59 * hash + Objects.hashCode(this.allLevels);
        hash = 59 * hash + Objects.hashCode(this.checkCvMapping);
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
        final ValidationForm other = (ValidationForm) obj;
        if (!Objects.equals(this.file, other.file)) {
            return false;
        }
        if (!Objects.equals(this.mappingFile, other.mappingFile)) {
            return false;
        }
        if (!Objects.equals(this.maxErrors, other.maxErrors)) {
            return false;
        }
        if (this.level != other.level) {
            return false;
        }
        if (!Objects.equals(this.allLevels, other.allLevels)) {
            return false;
        }
        if (!Objects.equals(this.checkCvMapping, other.checkCvMapping)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ValidationForm{" + "file=" + file + ", mappingFile=" + mappingFile + ", maxErrors=" + maxErrors + ", level=" + level + ", allLevels=" + allLevels + ", checkCvMapping=" + checkCvMapping + '}';
    }

}
