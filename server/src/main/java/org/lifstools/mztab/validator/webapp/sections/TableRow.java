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
package org.lifstools.mztab.validator.webapp.sections;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * @param index the 0-based row index in the file
 * @param cells the cells, in the order of {@link TablePage#columns()}; a cell the row's line does not have is
 * empty
 * @param lineNumber the 1-based line of the row in a working copy; null in the
 * sections of a validation result
 * @param cellCount for a working copy: the number of cells the row's line has, the prefix included, so a cell
 * index at or beyond it is a cell the line does not have; null in the sections of a validation result
 */
public record TableRow(int index, List<String> cells, @Nullable Long lineNumber,
    @Schema(nullable = true, description = "For a working copy: the number of cells the row's line has, the "
        + "prefix included; a cell index at or beyond it is a cell the line does not have")
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Integer cellCount) {

    public TableRow(int index, List<String> cells) {
        this(index, cells, null, null);
    }

    public TableRow(int index, List<String> cells, @Nullable Long lineNumber) {
        this(index, cells, lineNumber, null);
    }
}
