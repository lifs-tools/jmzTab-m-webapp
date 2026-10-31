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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link SectionReader#tablePage} on rows as a working copy has them: possibly shorter than the header. */
class TablePageTest {

    private static final List<String> HEADER = List.of("SML_ID", "a", "b", "c", "a");

    private static TableQuery query(String sort, List<String> columns, List<String> filters) {
        return new TableQuery(0, 10, sort, false, List.of(), columns, filters);
    }

    @Test
    void missingCellsOfShortRowsReadAsEmpty() {
        // rows are not padded to the header: a missing cell is "" where it is read
        List<String[]> rows = List.of(new String[]{"1"}, new String[]{"2", "x", "y", "z", "w"}, new String[]{"3", "q"});
        long[] lines = {10, 11, 12};

        TablePage page = SectionReader.tablePage(Section.SML, HEADER, rows, lines, query(null, List.of(), List.of()),
            new PositionQuery(List.of(1, 4, 5), null, List.of()));
        assertEquals(List.of("1", "", ""), page.rows().get(0).cells());
        assertEquals(List.of("2", "z", "w"), page.rows().get(1).cells());
        assertEquals(List.of("3", "", ""), page.rows().get(2).cells());

        TablePage byName = SectionReader.tablePage(Section.SML, HEADER, rows, lines, query("c", List.of("SML_ID", "c"),
            List.of()));
        // empty cells sort last
        assertEquals(List.of("2", "1", "3"), byName.rows().stream().map(r -> r.cells().get(0)).toList());

        TablePage filtered = SectionReader.tablePage(Section.SML, HEADER, rows, lines,
            query(null, List.of("SML_ID"), List.of("b:")), new PositionQuery(null, null, List.of("4:z")));
        assertEquals(1, filtered.totalRows());
        assertEquals(11L, filtered.rows().get(0).lineNumber());
    }

    @Test
    void aNameRequestedMoreOftenThanTheHeaderHoldsItIsRefused() {
        List<String[]> rows = List.<String[]>of(new String[]{"1", "x", "y", "z", "w"});
        TablePage twice = SectionReader.tablePage(Section.SML, HEADER, rows, null, query(null, List.of("a", "a"),
            List.of()));
        assertEquals(List.of("x", "w"), twice.rows().get(0).cells());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SectionReader.tablePage(Section.SML, HEADER, rows, null, query(null, List.of("a", "b", "a", "a"),
                List.of())));
        assertEquals("The column 'a' is requested 3 times, but the table has only 2 columns of that name.",
            e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SectionReader.tablePage(Section.SML, HEADER, rows, null,
            query(null, List.of("b", "b"), List.of())));
    }
}
