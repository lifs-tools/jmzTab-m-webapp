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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.LineEdit;

class LineEditsTest {

    @Test
    void mapsEveryEditOneToOne() {
        assertEquals(new EditOperation.SetCell(5, 2, "t", null), LineEdits.toOperation(new LineEdit.SetCell(5, 2, "t")));
        assertEquals(new EditOperation.InsertLine(3, "MTD\tx"), LineEdits.toOperation(new LineEdit.InsertLine(3, "MTD\tx")));
        assertEquals(new EditOperation.InsertLines(3, List.of("a", "b")),
            LineEdits.toOperation(new LineEdit.InsertLines(3, List.of("a", "b"))));
        assertEquals(new EditOperation.InsertColumn("SMH", 4, "n", "null"),
            LineEdits.toOperation(new LineEdit.InsertColumn("SMH", 4, "n", "null")));
    }
}
