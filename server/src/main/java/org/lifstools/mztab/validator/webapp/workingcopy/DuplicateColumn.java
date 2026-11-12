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
package org.lifstools.mztab.validator.webapp.workingcopy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A column name that a header of a working copy holds more than once.
 *
 * @param name the column name
 * @param positions the cell indexes of the columns of that name (the prefix is cell 0), in line order
 * @param conflictingRows the rows in which at least two of these cells hold a value (not empty, not
 * {@code null}) and the values differ
 * @param samples up to {@value WorkingCopyReader#MAX_DUPLICATE_SAMPLES} of the conflicting rows
 */
public record DuplicateColumn(String name, List<Integer> positions, long conflictingRows, List<Sample> samples) {

    /**
     * @param lineNumber the row's line
     * @param values the row's cell at each of the positions, in their order; empty where the row is shorter
     */
    @Schema(name = "DuplicateColumnSample")
    public record Sample(long lineNumber, List<String> values) {
    }
}
