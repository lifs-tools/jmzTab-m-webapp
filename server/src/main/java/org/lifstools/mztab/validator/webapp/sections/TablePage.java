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
 * A page of a table section.
 *
 * @param page 0-based page number
 * @param totalRows the number of rows after filtering
 * @param allColumns every column of the table
 * @param columns the returned columns
 * @param columnPositions for a working copy: the cell index in the line (the prefix is cell 0) of each returned
 * column, parallel to {@code columns}; absent for a validation's sections
 */
public record TablePage(Section section, int page, int size, long totalRows, List<String> allColumns,
    List<String> columns, List<TableRow> rows,
    @Schema(nullable = true, description = "For a working copy: the cell index in the line (the prefix is cell 0) "
        + "of each returned column, parallel to columns; absent for a validation's sections")
    @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<Integer> columnPositions) {

}
