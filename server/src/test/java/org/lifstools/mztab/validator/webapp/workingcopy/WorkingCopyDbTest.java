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

    @Test
    void deleteRangeIsOverflowSafe() throws Exception {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("f.db"))) {
            db.importLines(parsed(1, "\n"));
            assertThrows(IllegalArgumentException.class, () -> db.deleteLines(Long.MAX_VALUE - 1, 3));
            assertThrows(IllegalArgumentException.class, () -> db.deleteLines(2, 1));
            assertEquals(1, db.lineCount());
        }
    }

    @Test
    void writeToEqualsOriginalBytes() throws Exception {
        byte[] orig = "\uFEFFMTD\ta\r\nSML\t\t\nCOM\tx".getBytes(StandardCharsets.UTF_8);
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("g.db"))) {
            db.importLines(CopyText.read(new ByteArrayInputStream(orig), SourceEncoding.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            db.writeTo(out);
            assertArrayEquals(orig, out.toByteArray());
        }
    }

    @Test
    void importNumbersByPositionAndEditsAreValidated() throws Exception {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("h.db"))) {
            CopyText.Parsed p = new CopyText.Parsed(false,
                List.of(CopyText.line(7, "a", "\n"), CopyText.line(7, "b", "\n")));
            db.importLines(p);
            assertEquals("b", db.line(2).orElseThrow().text());
            assertThrows(IllegalArgumentException.class,
                () -> db.replaceLine(1, new Line(1, "?", List.of("a"), "\n\n")));
            assertThrows(IllegalArgumentException.class,
                () -> db.replaceLine(1, new Line(1, "?", List.of("a\tb"), "\n")));
            assertThrows(IllegalArgumentException.class,
                () -> db.insertLines(1, List.of(new Line(1, "?", List.of("a\nb"), ""))));
            assertThrows(IllegalArgumentException.class,
                () -> db.insertLines(1, List.of(new Line(1, "?", List.of("a\rb"), ""))));
            assertEquals(2, db.lineCount());
        }
    }

    @Test
    void historyIsAStackWithRedoDroppedByNewEntries() {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("h.db"))) {
            assertEquals(0, db.revision());
            db.setRevision(3);
            assertEquals(3, db.revision());
            assertTrue(db.peekUndo().isEmpty());
            db.addHistory("f1", "i1");
            db.addHistory("f2", "i2");
            WorkingCopyDb.HistoryEntry top = db.peekUndo().orElseThrow();
            assertEquals("i2", top.inverse());
            db.moveHistory(top.seq(), true);
            assertEquals("i1", db.peekUndo().orElseThrow().inverse());
            assertEquals("f2", db.peekRedo().orElseThrow().forward());
            db.addHistory("f3", "i3");
            assertTrue(db.peekRedo().isEmpty());
        }
    }

    /** {@code n} empty lines, then (if {@code endless}) empty lines without end. */
    private static java.io.InputStream newlines(long n, boolean endless) {
        return new java.io.InputStream() {
            private long left = n;

            @Override
            public int read() {
                if (left > 0 || endless) {
                    left--;
                    return '\n';
                }
                return -1;
            }
        };
    }

    @Test
    void importStreamsUpToTheLineCapAndNoEditGoesBeyondIt() throws Exception {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("cap.db"))) {
            db.importFrom(new ByteArrayInputStream("\uFEFFMTD\ta\nCOM\tb".getBytes(StandardCharsets.UTF_8)),
                SourceEncoding.UTF_8);
            assertEquals(2, db.lineCount());
            assertTrue(db.bom());
            assertEquals("COM\tb", db.line(2).orElseThrow().text());

            // an oversized file is refused while it is read, and the import leaves nothing of it
            assertThrows(TooLargeException.class, () -> db.importFrom(newlines(0, true), SourceEncoding.UTF_8));
            assertEquals(2, db.lineCount());
            assertEquals("COM\tb", db.line(2).orElseThrow().text());

            db.importFrom(newlines(CopyText.MAX_LINES, false), SourceEncoding.UTF_8);
            assertEquals(CopyText.MAX_LINES, db.lineCount());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> db.insertLines(1, List.of(CopyText.line(0, "COM\tx", "\n"))));
            assertEquals("A working copy has at most 2,000,000 lines.", e.getMessage());
            assertEquals(CopyText.MAX_LINES, db.lineCount());
        }
    }

    /** An output that, at its first byte, says so and waits until it is released. */
    private static final class PausingOutput extends java.io.OutputStream {

        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            bytes.write(b, off, len);
        }
    }

    @Test
    void anExportPausedMidStreamDoesNotBlockAWrite() throws Exception {
        try (WorkingCopyDb db = WorkingCopyDb.open(dir.resolve("export.db"))) {
            db.importLines(parsed(3000, "\n"));
            String before = dump(db, false);
            PausingOutput out = new PausingOutput();
            java.util.concurrent.CompletableFuture<Void> export = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    db.writeTo(out);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            try {
                assertTrue(out.started.await(10, java.util.concurrent.TimeUnit.SECONDS));
                long start = System.nanoTime();
                // a write transaction, as an edit batch is, commits while the export waits
                db.inTransaction(() -> {
                    db.replaceLine(2, CopyText.line(2, "MTD\tchanged\tz", "\n"));
                    db.setRevision(1);
                    return null;
                });
                assertTrue(System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(2),
                    "the write waited for the export");
                assertEquals(1, db.revision());
            } finally {
                out.release.countDown();
            }
            export.get(10, java.util.concurrent.TimeUnit.SECONDS);
            // the export is the text of one snapshot: the one it started on
            assertEquals(before, out.bytes.toString(StandardCharsets.UTF_8));
            assertEquals("MTD\tchanged\tz", db.line(2).orElseThrow().text());
        }
    }
}
