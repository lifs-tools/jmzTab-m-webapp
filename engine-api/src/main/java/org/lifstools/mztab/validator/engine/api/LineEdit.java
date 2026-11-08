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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A text edit of a working copy, as an engine proposes it. The types, names and fields are those of the webapp's
 * {@code EditOperation}, so the webapp applies the edits as they come. Line numbers are 1-based and refer to the
 * text as it is when the edit runs: the edits before it in the list have taken effect. Cell 0 of a line is its
 * prefix (MTD, SML, ...), so a metadata value is cell 2. In JSON the type is the property {@code op}.
 */
@Schema(description = "A text edit of a working copy; op names the edit, as in the webapp's edit operations.",
    oneOf = {LineEdit.SetCell.class, LineEdit.InsertLine.class, LineEdit.InsertColumn.class},
    discriminatorProperty = "op",
    discriminatorMapping = {
        @DiscriminatorMapping(value = "SetCell", schema = LineEdit.SetCell.class),
        @DiscriminatorMapping(value = "InsertLine", schema = LineEdit.InsertLine.class),
        @DiscriminatorMapping(value = "InsertColumn", schema = LineEdit.InsertColumn.class)
    })
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LineEdit.SetCell.class, name = "SetCell"),
    @JsonSubTypes.Type(value = LineEdit.InsertLine.class, name = "InsertLine"),
    @JsonSubTypes.Type(value = LineEdit.InsertColumn.class, name = "InsertColumn")
})
public sealed interface LineEdit {

    /**
     * Sets cell {@code cell} of line {@code line} (at least 1; at most the number of cells, which appends one).
     *
     * @param line the line number
     * @param cell the cell index
     * @param text the new cell text
     */
    record SetCell(long line, int cell, String text) implements LineEdit {
    }

    /**
     * Inserts a line (tabs split it into cells) so that it becomes line {@code before}.
     *
     * @param before the number the new line gets
     * @param text the line, without a line ending
     */
    record InsertLine(long before, String text) implements LineEdit {
    }

    /**
     * Inserts a column at cell index {@code position} in a SMH, SFH or SEH header and its rows.
     *
     * @param header the header prefix
     * @param position the cell index of the new column
     * @param name the column name
     * @param fill the text of the new cell in every row
     */
    record InsertColumn(String header, int position, String name, String fill) implements LineEdit {
    }
}
