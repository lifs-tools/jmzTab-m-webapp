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

import org.lifstools.mztab.validator.engine.api.LineEdit;

/** Maps the line edits an engine proposes to the webapp's edit operations. */
public final class LineEdits {

    private LineEdits() {
    }

    /**
     * The edit operation of the same name and fields; a {@code SetCell} has no header check ({@code header} is null).
     *
     * @param edit an engine's line edit
     * @return the operation that applies it
     */
    public static EditOperation toOperation(LineEdit edit) {
        return switch (edit) {
            case LineEdit.SetCell e -> new EditOperation.SetCell(e.line(), e.cell(), e.text(), null);
            case LineEdit.InsertLine e -> new EditOperation.InsertLine(e.before(), e.text());
            case LineEdit.InsertLines e -> new EditOperation.InsertLines(e.before(), e.lines());
            case LineEdit.InsertColumn e -> new EditOperation.InsertColumn(e.header(), e.position(), e.name(), e.fill());
        };
    }
}
