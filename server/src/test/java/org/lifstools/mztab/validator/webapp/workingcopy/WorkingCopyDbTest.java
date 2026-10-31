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

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkingCopyDbTest {

    @TempDir
    Path dir;

    private static CopyText.Parsed parsed(int n, String eol) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("MTD\tkey").append(i).append("\tv").append(i).append(eol);
        }
        return CopyText.read(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
            SourceEncoding.UTF_8);
    }

    private static String dump(WorkingCopyDb db, boolean bom) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CopyText.write(bom, db.allLines(), out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void importAndReadBack() throws Exception {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("a.db"))) {
            db.importLines(parsed(1000, "\n"));
            assertEquals(1000, db.lineCount());
            List<Line> page = db.lines(998, 5);
            assertEquals(List.of(998L, 999L, 1000L), page.stream().map(Line::number).toList());
            assertEquals("MTD\tkey999\tv999", db.line(999).orElseThrow().text());
            assertTrue(db.line(1001).isEmpty());
            assertEquals(1000, new ArrayList<>(iterate(db)).size());
        }
    }

    private static List<Line> iterate(WorkingCopyDb db) {
        List<Line> all = new ArrayList<>();
        db.allLines().forEachRemaining(all::add);
        return all;
    }

    @Test
    void insertAndDeleteRenumber() throws Exception {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("b.db"))) {
            db.importLines(parsed(10, "\n"));
            db.insertLines(3, List.of(CopyText.line(0, "COM\tx", "\n"), CopyText.line(0, "COM\ty", "\n")));
            assertEquals(12, db.lineCount());
            assertEquals("MTD\tkey3\tv3", db.line(5).orElseThrow().text());
            assertEquals("COM\tx", db.line(3).orElseThrow().text());
            db.deleteLines(1, 1);
            assertEquals("MTD\tkey3\tv3", db.line(4).orElseThrow().text());
            assertEquals(11, db.lineCount());
            db.insertLines(12, List.of(CopyText.line(0, "COM\tend", "")));
            assertEquals("COM\tend", db.line(12).orElseThrow().text());
            assertThrows(IllegalArgumentException.class, () -> db.deleteLines(12, 2));
            assertThrows(IllegalArgumentException.class, () -> db.insertLines(14, List.of()));
        }
    }

    @Test
    void editKeepsOtherLinesBytes() throws Exception {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("c.db"))) {
            db.importLines(parsed(8, "\r\n"));
            String before = dump(db, false);
            db.replaceLine(5, CopyText.line(5, "MTD\tchanged\tz", "\r\n"));
            String[] a = before.split("(?<=\r\n)");
            String[] b = dump(db, false).split("(?<=\r\n)");
            assertEquals(a.length, b.length);
            for (int i = 0; i < a.length; i++) {
                if (i == 4) {
                    assertEquals("MTD\tchanged\tz\r\n", b[i]);
                } else {
                    assertEquals(a[i], b[i]);
                }
            }
        }
    }

    @Test
    void reopenKeepsContent() throws Exception {
        Path f = dir.resolve("d.db");
        try (WorkingCopyDb db = WorkingCopyDb.open(f)) {
            db.importLines(parsed(20, "\n"));
            db.setMeta("name", "x.mztab");
        }
        try (WorkingCopyDb db = WorkingCopyDb.open(f)) {
            assertEquals(20, db.lineCount());
            assertEquals("x.mztab", db.meta("name"));
            assertNull(db.meta("absent"));
            assertFalse(db.bom());
        }
    }

    @Test
    void transactionRollsBackAndRethrows() throws Exception {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("e.db"))) {
            db.importLines(parsed(5, "\n"));
            assertThrows(IllegalStateException.class, () -> db.inTransaction(() -> {
                db.deleteLines(1, 3);
                throw new IllegalStateException("boom");
            }));
            assertEquals(5, db.lineCount());
            assertEquals(Integer.valueOf(2), db.inTransaction(() -> {
                db.deleteLines(1, 3);
                return 2;
            }));
            assertEquals(2, db.lineCount());
        }
    }
}
