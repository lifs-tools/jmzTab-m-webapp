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

import org.lifstools.mztab.validator.webapp.service.ValidationService;
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
    private ValidationService.MzTabVersion mzTabVersion = ValidationService.MzTabVersion.MZTAB_2_0;
    private Integer maxErrors = 100;
    private ValidationLevel level = ValidationLevel.INFO;
    private Boolean checkCvMapping = false;
    private final List<ValidationLevel> allLevels = Arrays.asList(
        ValidationLevel.values());
    private final List<ValidationService.MzTabVersion> allVersions = Arrays.
        asList(ValidationService.MzTabVersion.values());

    public ValidationForm() {
        this(null, null, ValidationService.MzTabVersion.MZTAB_2_0, 100,
            ValidationLevel.INFO, false);
    }

    public ValidationForm(MultipartFile file, MultipartFile mappingFile,
        ValidationService.MzTabVersion mzTabVersion, Integer maxErrors,
        ValidationLevel level, Boolean checkCvMapping) {
        this.file = file;
        this.mappingFile = mappingFile;
        this.mzTabVersion = mzTabVersion;
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

    public ValidationService.MzTabVersion getMzTabVersion() {
        return mzTabVersion;
    }

    public void setMzTabVersion(ValidationService.MzTabVersion mzTabVersion) {
        this.mzTabVersion = mzTabVersion;
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

    public List<ValidationService.MzTabVersion> getAllVersions() {
        return this.allVersions;
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
        hash = 59 * hash + Objects.hashCode(this.mzTabVersion);
        hash = 59 * hash + Objects.hashCode(this.maxErrors);
        hash = 59 * hash + Objects.hashCode(this.level);
        hash = 59 * hash + Objects.hashCode(this.allLevels);
        hash = 59 * hash + Objects.hashCode(this.allVersions);
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
        if (this.mzTabVersion != other.mzTabVersion) {
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
        if (!Objects.equals(this.allVersions, other.allVersions)) {
            return false;
        }
        if (!Objects.equals(this.checkCvMapping, other.checkCvMapping)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "ValidationForm{" + "file=" + file + ", mappingFile=" + mappingFile + ", mzTabVersion=" + mzTabVersion + ", maxErrors=" + maxErrors + ", level=" + level + ", allLevels=" + allLevels + ", allVersions=" + allVersions + ", checkCvMapping=" + checkCvMapping + '}';
    }

}
