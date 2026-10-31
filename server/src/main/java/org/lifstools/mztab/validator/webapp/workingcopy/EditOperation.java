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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A text edit of a working copy. Line numbers are 1-based and refer to the
 * text as it is when the operation runs (earlier operations of the batch have
 * taken effect). Cell 0 of a line is its prefix (MTD, SML, ...). In JSON the
 * type is the property {@code op}, named like the record.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EditOperation.SetCell.class, name = "SetCell"),
    @JsonSubTypes.Type(value = EditOperation.SetMetadataValue.class, name = "SetMetadataValue"),
    @JsonSubTypes.Type(value = EditOperation.RenameMetadataKey.class, name = "RenameMetadataKey"),
    @JsonSubTypes.Type(value = EditOperation.InsertLine.class, name = "InsertLine"),
    @JsonSubTypes.Type(value = EditOperation.DeleteLine.class, name = "DeleteLine"),
    @JsonSubTypes.Type(value = EditOperation.MoveRow.class, name = "MoveRow"),
    @JsonSubTypes.Type(value = EditOperation.InsertColumn.class, name = "InsertColumn"),
    @JsonSubTypes.Type(value = EditOperation.DeleteColumn.class, name = "DeleteColumn"),
    @JsonSubTypes.Type(value = EditOperation.RenameColumn.class, name = "RenameColumn"),
    @JsonSubTypes.Type(value = EditOperation.JoinWithPrevious.class, name = "JoinWithPrevious"),
    @JsonSubTypes.Type(value = EditOperation.SplitCells.class, name = "SplitCells"),
    @JsonSubTypes.Type(value = EditOperation.SetRawLine.class, name = "SetRawLine")})
public sealed interface EditOperation {

    /** Sets cell {@code cell} (at least 1; at most the number of cells, which appends one). */
    record SetCell(long line, int cell, String text) implements EditOperation {
    }

    /** Sets the value (cell 2) of an MTD line. */
    record SetMetadataValue(long line, String value) implements EditOperation {
    }

    /** Sets the key (cell 1) of an MTD line. */
    record RenameMetadataKey(long line, String key) implements EditOperation {
    }

    /** Inserts a line (tabs split it into cells) so that it becomes line {@code before}. */
    record InsertLine(long before, String text) implements EditOperation {
    }

    record DeleteLine(long line) implements EditOperation {
    }

    /** Moves a line to stand before line {@code before} (numbered as before the move). */
    record MoveRow(long line, long before) implements EditOperation {
    }

    /** Inserts a column at cell index {@code position} in a SMH, SFH or SEH header and its rows. */
    record InsertColumn(String header, int position, String name, String fill) implements EditOperation {
    }

    record DeleteColumn(String header, String name) implements EditOperation {
    }

    record RenameColumn(String header, String name, String newName) implements EditOperation {
    }

    /** Appends the line to the previous one with no separator. */
    record JoinWithPrevious(long line) implements EditOperation {
    }

    /** Splits the cell after the prefix at its first run of spaces. */
    record SplitCells(long line) implements EditOperation {
    }

    /** Replaces the whole line text (tabs split it into cells); the line ending stays. */
    record SetRawLine(long line, String text) implements EditOperation {
    }
}
