/*
 * Copyright 2026 LIFS Tools Consortium.
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
package org.lifstools.mztab.validator.engine.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;
import lombok.Data;

/**
 * Where in an mzTab file a validation message applies. The JSON is identical
 * to jmzTab-m's {@code ValidationLocation}, and so is its schema: every field
 * may be absent or null.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"section", "row_index", "column", "metadata_key", "metadata_group", "element_path"})
public class ValidationLocation {

    /** The mzTab section a message refers to. */
    public enum Section {
        MTD, SMH, SML, SFH, SMF, SEH, SME, COM, FILE;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Schema(types = {"string", "null"})
    @JsonProperty("section")
    private Section section;

    @Schema(types = {"integer", "null"}, minimum = "0")
    @JsonProperty("row_index")
    private Integer rowIndex;

    @Schema(types = {"string", "null"})
    @JsonProperty("column")
    private String column;

    @Schema(types = {"string", "null"})
    @JsonProperty("metadata_key")
    private String metadataKey;

    @Schema(types = {"string", "null"})
    @JsonProperty("metadata_group")
    private String metadataGroup;

    @Schema(types = {"string", "null"})
    @JsonProperty("element_path")
    private String elementPath;
}
