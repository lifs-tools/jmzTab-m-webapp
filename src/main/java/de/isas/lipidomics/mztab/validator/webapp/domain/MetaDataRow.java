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
package de.isas.lipidomics.mztab.validator.webapp.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 *
 * @author nilshoffmann
 */
public class MetaDataRow {
    private final Long lineNumber;
    private final String prefix;
    private final String key;
    private final String value;

    public MetaDataRow(Long lineNumber, String prefix, String key, String value) {
        this.lineNumber = lineNumber;
        this.prefix = prefix;
        this.key = key;
        this.value = value;
    }

    public Long getLineNumber() {
        return lineNumber;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 89 * hash + Objects.hashCode(this.lineNumber);
        hash = 89 * hash + Objects.hashCode(this.prefix);
        hash = 89 * hash + Objects.hashCode(this.key);
        hash = 89 * hash + Objects.hashCode(this.value);
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
        final MetaDataRow other = (MetaDataRow) obj;
        if (!Objects.equals(this.prefix, other.prefix)) {
            return false;
        }
        if (!Objects.equals(this.key, other.key)) {
            return false;
        }
        if (!Objects.equals(this.value, other.value)) {
            return false;
        }
        if (!Objects.equals(this.lineNumber, other.lineNumber)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return "MetaDataRow{" + "lineNumber=" + lineNumber + ", prefix=" + prefix + ", key=" + key + ", value=" + value + '}';
    }
    
    public List<String> toList() {
        return Arrays.asList(""+lineNumber, prefix, key, value);
    }
    
    
}
