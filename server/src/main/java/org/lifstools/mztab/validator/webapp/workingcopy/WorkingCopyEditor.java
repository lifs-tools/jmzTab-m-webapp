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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.*;

/**
 * Applies repair operations to the text of a working copy, with revisions and
 * undo/redo. Edits are text edits: lines and cells are changed, nothing is
 * interpreted as mzTab-M.
 *
 * <p>
 * The revision starts at 0 after import; every applied batch, undo and redo
 * produces {@code revision + 1}. A call must name the current revision, else it
 * throws {@link StaleRevisionException}. A batch is one transaction: an invalid
 * operation throws {@link IllegalArgumentException} and nothing, not even the
 * revision, changes.
 *
 * <p>
 * Undo works on line-level images, not on inverse operations: every primitive
 * change (replace, insert or delete lines) records the lines it replaced or
 * removed, with their exact line endings, and what it inserted. Undo plays the
 * recorded before-images back, so the text becomes byte-identical; redo plays
 * the after-images. Undo or redo without anything to do throws
 * {@link IllegalStateException}.
 *
 * <p>
 * Choices: a cell index is 0 for the prefix; {@code SetCell} may address an
 * existing cell or the one just past the end (which appends), a larger index is
 * rejected. Where several header lines of one kind exist, column operations use
 * the first and apply to every row of that kind. {@code MoveRow} keeps the line
 * endings by position (only the content moves), so a moved last line does not
 * leave a missing ending in the middle. Deleting the unterminated last line
 * leaves the new last line with its ending. Limits: 5,000 operations per batch,
 * 100,000 characters per cell, 1,000,000 per line; a tab only in
 * {@code SetRawLine} and {@code InsertLine} text; a CR or LF nowhere.
 */
public final class WorkingCopyEditor {

    public static final int MAX_OPERATIONS = 5_000;
    public static final int MAX_CELL = 100_000;
    public static final int MAX_LINE = 1_000_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Step>> STEPS = new TypeReference<>() {
    };
    private static final Map<String, String> ROW_SECTION = Map.of("SMH", "SML", "SFH", "SMF", "SEH", "SME");

    /** A stored line image: section, cells and ending are kept as they were. */
    record StoredLine(String section, List<String> cells, String eol) {

        static StoredLine of(Line l) {
            return new StoredLine(l.section(), l.cells(), l.eol());
        }
    }

    /** One recorded primitive change: type is REPLACE, INSERT or DELETE. */
    record Step(String type, long at, int count, List<StoredLine> lines) {
    }

    /** Applies the batch to the current revision; returns the new revision. */
    public long apply(WorkingCopyDb db, EditBatch batch) {
        if (batch == null || batch.operations() == null || batch.operations().isEmpty()) {
            throw new IllegalArgumentException("A batch needs at least one operation");
        }
        if (batch.operations().size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("A batch has at most " + MAX_OPERATIONS + " operations");
        }
        if (batch.operations().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("An operation must not be null");
        }
        return db.inTransaction(() -> {
            long current = requireRevision(db, batch.revision());
            Recorder r = new Recorder(db);
            for (EditOperation op : batch.operations()) {
                execute(r, op);
            }
            Collections.reverse(r.inverse);
            db.addHistory(toJson(r.forward), toJson(r.inverse));
            db.setRevision(current + 1);
            return current + 1;
        });
    }

    /** Reverts the newest applied change; returns the new revision. */
    public long undo(WorkingCopyDb db, long revision) {
        return db.inTransaction(() -> {
            long current = requireRevision(db, revision);
            WorkingCopyDb.HistoryEntry e = db.peekUndo()
                .orElseThrow(() -> new IllegalStateException("There is nothing to undo"));
            play(db, e.inverse());
            db.moveHistory(e.seq(), true);
            db.setRevision(current + 1);
            return current + 1;
        });
    }

    /** Re-applies the change undone most recently; returns the new revision. */
    public long redo(WorkingCopyDb db, long revision) {
        return db.inTransaction(() -> {
            long current = requireRevision(db, revision);
            WorkingCopyDb.HistoryEntry e = db.peekRedo()
                .orElseThrow(() -> new IllegalStateException("There is nothing to redo"));
            play(db, e.forward());
            db.moveHistory(e.seq(), false);
            db.setRevision(current + 1);
            return current + 1;
        });
    }

    public boolean canUndo(WorkingCopyDb db) {
        return db.peekUndo().isPresent();
    }

    public boolean canRedo(WorkingCopyDb db) {
        return db.peekRedo().isPresent();
    }

    private static long requireRevision(WorkingCopyDb db, long given) {
        long current = db.revision();
        if (current != given) {
            throw new StaleRevisionException(current, given);
        }
        return current;
    }

    // ---- recording and playing line-level steps ----

    /** Applies primitive changes to the database and records both directions. */
    private static final class Recorder {

        final WorkingCopyDb db;
        final List<Step> forward = new ArrayList<>();
        final List<Step> inverse = new ArrayList<>();

        Recorder(WorkingCopyDb db) {
            this.db = db;
        }

        void replace(long n, Line line) {
            Line old = db.line(n).orElseThrow(() -> new IllegalArgumentException("There is no line " + n));
            db.replaceLine(n, line);
            forward.add(new Step("REPLACE", n, 1, List.of(StoredLine.of(line))));
            inverse.add(new Step("REPLACE", n, 1, List.of(StoredLine.of(old))));
        }

        void insert(long before, List<Line> lines) {
            db.insertLines(before, lines);
            forward.add(new Step("INSERT", before, lines.size(), lines.stream().map(StoredLine::of).toList()));
            inverse.add(new Step("DELETE", before, lines.size(), List.of()));
        }

        void delete(long from, int count) {
            List<Line> old = db.lines(from, count);
            db.deleteLines(from, count);
            forward.add(new Step("DELETE", from, count, List.of()));
            inverse.add(new Step("INSERT", from, count, old.stream().map(StoredLine::of).toList()));
        }
    }

    private static void play(WorkingCopyDb db, String json) {
        for (Step s : fromJson(json)) {
            switch (s.type()) {
                case "REPLACE" -> db.replaceLine(s.at(), toLine(s.at(), s.lines().get(0)));
                case "INSERT" -> {
                    List<Line> lines = new ArrayList<>();
                    for (int i = 0; i < s.lines().size(); i++) {
                        lines.add(toLine(s.at() + i, s.lines().get(i)));
                    }
                    db.insertLines(s.at(), lines);
                }
                case "DELETE" -> db.deleteLines(s.at(), s.count());
                default -> throw new IllegalStateException("Unknown history step " + s.type());
            }
        }
    }

    private static Line toLine(long number, StoredLine l) {
        return new Line(number, l.section(), l.cells(), l.eol());
    }

    private static String toJson(List<Step> steps) {
        try {
            return JSON.writeValueAsString(steps);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Step> fromJson(String json) {
        try {
            return JSON.readValue(json, STEPS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- operations ----

    private void execute(Recorder r, EditOperation op) {
        switch (op) {
            case SetCell o -> setCell(r, o.line(), o.cell(), o.text());
            case SetMetadataValue o -> setMetadata(r, o.line(), 2, o.value());
            case RenameMetadataKey o -> setMetadata(r, o.line(), 1, o.key());
            case InsertLine o -> insertLine(r, o.before(), o.text());
            case DeleteLine o -> {
                lineAt(r.db, o.line());
                r.delete(o.line(), 1);
            }
            case MoveRow o -> moveRow(r, o.line(), o.before());
            case InsertColumn o -> insertColumn(r, o);
            case DeleteColumn o -> deleteColumn(r, o);
            case RenameColumn o -> renameColumn(r, o);
            case JoinWithPrevious o -> join(r, o.line());
            case SplitCells o -> splitCells(r, o.line());
            case SetRawLine o -> setRaw(r, o.line(), o.text());
        }
    }

    private static Line lineAt(WorkingCopyDb db, long n) {
        return db.line(n).orElseThrow(() -> new IllegalArgumentException("There is no line " + n));
    }

    private static Line rebuild(long n, List<String> cells, String eol) {
        return CopyText.line(n, String.join("\t", cells), eol);
    }

    private static String cell(String s) {
        if (s == null || s.indexOf('\t') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A cell must be text without a tab or a line break");
        }
        if (s.length() > MAX_CELL) {
            throw new IllegalArgumentException("A cell has at most " + MAX_CELL + " characters");
        }
        return s;
    }

    private static String name(String s) {
        if (cell(s).isEmpty()) {
            throw new IllegalArgumentException("A column name must not be empty");
        }
        return s;
    }

    private static String rawLine(String s) {
        if (s == null || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("A line must be text without a line break");
        }
        if (s.length() > MAX_LINE) {
            throw new IllegalArgumentException("A line has at most " + MAX_LINE + " characters");
        }
        for (String c : s.split("\t", -1)) {
            cell(c);
        }
        return s;
    }

    private void setCell(Recorder r, long n, int cell, String text) {
        Line l = lineAt(r.db, n);
        cell(text);
        List<String> cells = new ArrayList<>(l.cells());
        if (cell < 1 || cell > cells.size()) {
            throw new IllegalArgumentException("Line " + n + " has no cell " + cell);
        }
        if (cell == cells.size()) {
            cells.add(text);
        } else {
            cells.set(cell, text);
        }
        r.replace(n, rebuild(n, cells, l.eol()));
    }

    private void setMetadata(Recorder r, long n, int index, String value) {
        Line l = lineAt(r.db, n);
        cell(value);
        if (!"MTD".equals(l.section())) {
            throw new IllegalArgumentException("Line " + n + " is not a metadata line");
        }
        List<String> cells = new ArrayList<>(l.cells());
        while (cells.size() <= index) {
            cells.add("");
        }
        cells.set(index, value);
        r.replace(n, rebuild(n, cells, l.eol()));
    }

    /** The ending used for a line that is made the last-but-one: line 1's, else LF. */
    private static String terminator(WorkingCopyDb db) {
        String first = db.line(1).map(Line::eol).orElse("");
        return first.isEmpty() ? "\n" : first;
    }

    private void insertLine(Recorder r, long before, String text) {
        rawLine(text);
        long count = r.db.lineCount();
        if (before < 1 || before > count + 1) {
            throw new IllegalArgumentException("Cannot insert before line " + before);
        }
        String eol;
        if (before <= count) {
            eol = lineAt(r.db, before).eol();
            if (eol.isEmpty()) {
                eol = terminator(r.db);
            }
        } else if (count == 0) {
            eol = "";
        } else {
            Line last = lineAt(r.db, count);
            if (last.eol().isEmpty()) {
                eol = "";
                r.replace(count, new Line(count, last.section(), last.cells(), terminator(r.db)));
            } else {
                eol = last.eol();
            }
        }
        r.insert(before, List.of(CopyText.line(before, text, eol)));
    }

    private void setRaw(Recorder r, long n, String text) {
        Line l = lineAt(r.db, n);
        rawLine(text);
        r.replace(n, CopyText.line(n, text, l.eol()));
    }

    private void moveRow(Recorder r, long line, long before) {
        long count = r.db.lineCount();
        lineAt(r.db, line);
        if (before < 1 || before > count + 1) {
            throw new IllegalArgumentException("Cannot move before line " + before);
        }
        long dest = before > line ? before - 1 : before;
        if (dest == line) {
            return;
        }
        long lo = Math.min(line, dest);
        long hi = Math.max(line, dest);
        List<Line> range = r.db.lines(lo, (int) (hi - lo + 1));
        List<List<String>> cells = new ArrayList<>();
        range.forEach(l -> cells.add(l.cells()));
        List<String> moved = cells.remove((int) (line - lo));
        cells.add((int) (dest - lo), moved);
        // the endings stay where they are, only the content moves
        for (int i = 0; i < range.size(); i++) {
            r.replace(lo + i, rebuild(lo + i, cells.get(i), range.get(i).eol()));
        }
    }

    private void join(Recorder r, long n) {
        Line cur = lineAt(r.db, n);
        if (n < 2) {
            throw new IllegalArgumentException("Line " + n + " has no previous line");
        }
        Line prev = lineAt(r.db, n - 1);
        String text = prev.text() + cur.text();
        if (text.length() > MAX_LINE) {
            throw new IllegalArgumentException("A line has at most " + MAX_LINE + " characters");
        }
        r.replace(n - 1, CopyText.line(n - 1, text, cur.eol()));
        r.delete(n, 1);
    }

    private void splitCells(Recorder r, long n) {
        Line l = lineAt(r.db, n);
        if (l.cells().size() < 2) {
            throw new IllegalArgumentException("Line " + n + " has no cell after the prefix");
        }
        String c = l.cells().get(1);
        int start = c.indexOf(' ');
        if (start < 1) {
            throw new IllegalArgumentException("Cell 1 of line " + n + " has no space to split at");
        }
        int end = start;
        while (end < c.length() && c.charAt(end) == ' ') {
            end++;
        }
        if (end == c.length()) {
            throw new IllegalArgumentException("Cell 1 of line " + n + " has nothing after its spaces");
        }
        List<String> cells = new ArrayList<>(l.cells());
        cells.set(1, c.substring(0, start));
        cells.add(2, c.substring(end));
        r.replace(n, rebuild(n, cells, l.eol()));
    }

    // ---- columns ----

    private static String rowSection(String header) {
        String rows = header == null ? null : ROW_SECTION.get(header);
        if (rows == null) {
            throw new IllegalArgumentException("A header must be SMH, SFH or SEH");
        }
        return rows;
    }

    /** The first line of the section, or an error. */
    private static Line firstOf(WorkingCopyDb db, String section) {
        Iterator<Line> it = db.allLines();
        while (it.hasNext()) {
            Line l = it.next();
            if (section.equals(l.section())) {
                return l;
            }
        }
        throw new IllegalArgumentException("There is no " + section + " line");
    }

    private static int columnIndex(Line header, String name) {
        if (name == null) {
            throw new IllegalArgumentException("A column name is required");
        }
        for (int i = 1; i < header.cells().size(); i++) {
            if (header.cells().get(i).equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("There is no column " + name + " in " + header.section());
    }

    private void insertColumn(Recorder r, InsertColumn o) {
        String rows = rowSection(o.header());
        name(o.name());
        cell(o.fill());
        Line header = firstOf(r.db, o.header());
        if (o.position() < 1 || o.position() > header.cells().size()) {
            throw new IllegalArgumentException("A column position must be between 1 and " + header.cells().size());
        }
        List<String> hc = new ArrayList<>(header.cells());
        hc.add(o.position(), o.name());
        r.replace(header.number(), rebuild(header.number(), hc, header.eol()));
        forEachRow(r, rows, l -> {
            List<String> cells = new ArrayList<>(l.cells());
            while (cells.size() < o.position()) {
                cells.add("");
            }
            cells.add(o.position(), o.fill());
            return cells;
        });
    }

    private void deleteColumn(Recorder r, DeleteColumn o) {
        String rows = rowSection(o.header());
        Line header = firstOf(r.db, o.header());
        int idx = columnIndex(header, o.name());
        List<String> hc = new ArrayList<>(header.cells());
        hc.remove(idx);
        r.replace(header.number(), rebuild(header.number(), hc, header.eol()));
        forEachRow(r, rows, l -> {
            List<String> cells = new ArrayList<>(l.cells());
            if (cells.size() > idx) {
                cells.remove(idx);
            }
            return cells;
        });
    }

    private void renameColumn(Recorder r, RenameColumn o) {
        rowSection(o.header());
        name(o.newName());
        Line header = firstOf(r.db, o.header());
        int idx = columnIndex(header, o.name());
        List<String> hc = new ArrayList<>(header.cells());
        hc.set(idx, o.newName());
        r.replace(header.number(), rebuild(header.number(), hc, header.eol()));
    }

    /** Rewrites every line of the section; replacing in place keeps the numbering stable while paging. */
    private void forEachRow(Recorder r, String section, java.util.function.Function<Line, List<String>> change) {
        Iterator<Line> it = r.db.allLines();
        while (it.hasNext()) {
            Line l = it.next();
            if (section.equals(l.section())) {
                r.replace(l.number(), rebuild(l.number(), change.apply(l), l.eol()));
            }
        }
    }
}
