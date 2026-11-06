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

/** The duplicate-column scan of {@link WorkingCopyReader} on rows shorter than the header. */
class WorkingCopyReaderTest {

    private static Line line(long n, String text) {
        return CopyText.line(n, text, "\n");
    }

    @Test
    void shortRowsUnderAWideHeaderHoldNoValueInTheirMissingCells() {
        // "a" at cells 1, 3 and 5; rows end early
        List<Line> lines = List.of(
            line(1, "SML\tx"),
            line(2, "SMH\ta\tb\ta\tc\ta"),
            line(3, "SML\t1"),
            line(4, "SML\t1\t\t2"),
            line(5, "SML\t1\t\tnull\t\t1"),
            line(6, "SML\t1\t\t1\t\t2"));
        List<DuplicateColumn> found = WorkingCopyReader.duplicates("SMH", lines);
        assertEquals(1, found.size());
        DuplicateColumn a = found.get(0);
        assertEquals("a", a.name());
        assertEquals(List.of(1, 3, 5), a.positions());
        // line 1 ("x" only) holds one value; line 4 and 6 disagree; line 5 has null in between
        assertEquals(2, a.conflictingRows());
        assertEquals(List.of(4L, 6L), a.samples().stream().map(DuplicateColumn.Sample::lineNumber).toList());
        assertEquals(List.of("1", "2", ""), a.samples().get(0).values());
        assertEquals(List.of("1", "1", "2"), a.samples().get(1).values());
    }

    @Test
    void aRowBeforeTheHeaderIsScannedToo() {
        List<DuplicateColumn> found = WorkingCopyReader.duplicates("SMH",
            List.of(line(1, "SML\t1\t2"), line(2, "SMH\ta\ta")));
        assertEquals(1, found.get(0).conflictingRows());
    }
}
