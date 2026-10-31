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
import org.springframework.lang.Nullable;

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
 * Write amplification is bounded: replacing a line by identical content records
 * nothing; the images one batch records may total at most max(64 Mi, 3 x the
 * copy's text) characters (else {@link IllegalArgumentException}); at most 100
 * batches stay undoable and the history is pruned, oldest first, to
 * max(64 Mi, 4 x text) characters. {@code SplitCells} skips leading spaces of
 * the cell (they stay in the first part) and splits at the first run of spaces
 * after the first non-space. {@code MoveRow} moves the line with its own ending;
 * only if the file ends without a line ending is the ending of the last line
 * kept last (one extra replace).
 *
 * <p>
 * Choices: a cell index is 0 for the prefix; {@code SetCell} may address an
 * existing cell or the one just past the end (which appends), a larger index is
 * rejected. Where several header lines of one kind exist, column operations use
 * the first and apply to every row of that kind. No operation creates a second
 * column of a name a header already has; a file loaded with such columns is
 * repaired with a positioned {@code DeleteColumn} or with {@code MergeColumns}. Deleting the unterminated last line
 * leaves the new last line with its ending. Limits: 5,000 operations per batch,
 * 100,000 characters per cell, 1,000,000 characters and 20,000 cells per line, 2,000,000 lines per copy; a tab only in
 * {@code SetRawLine} and {@code InsertLine} text; a CR or LF nowhere.
 */
public final class WorkingCopyEditor {

    public static final int MAX_OPERATIONS = 5_000;
    public static final int MAX_CELL = 100_000;
    public static final int MAX_LINE = 1_000_000;
    /** Most undoable batches kept per copy. */
    public static final int MAX_UNDO = 100;
    private static final long MIN_BUDGET_CHARS = 64L << 20;

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
            long textChars = db.textChars();
            Recorder r = new Recorder(db, Math.max(MIN_BUDGET_CHARS, 3 * textChars));
            for (EditOperation op : batch.operations()) {
                execute(r, op);
            }
            Collections.reverse(r.inverse);
            String forward = toJson(r.forward);
            String inverse = toJson(r.inverse);
            if (forward.length() + (long) inverse.length() > Math.max(MIN_BUDGET_CHARS, 3 * textChars)) {
                throw new IllegalArgumentException("The batch changes too much text at once; split it");
            }
            db.addHistory(forward, inverse);
            db.pruneHistory(MAX_UNDO, Math.max(MIN_BUDGET_CHARS, 4 * textChars));
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

    /** Whether there is a change to undo; reads no history entry (undo then loads the one it plays, once). */
    public boolean canUndo(WorkingCopyDb db) {
        return db.hasUndo();
    }

    /** Whether there is a change to redo; as {@link #canUndo}. */
    public boolean canRedo(WorkingCopyDb db) {
        return db.hasRedo();
    }

    private static long requireRevision(WorkingCopyDb db, long given) {
        long current = db.revision();
        if (current != given) {
            throw new StaleRevisionException(current, given);
        }
        return current;
    }

    // ---- recording and playing line-level steps ----

    /**
     * Applies primitive changes to the database and records both directions.
     * Every written line is checked against the line limit, and the characters
     * of all recorded images against a per-batch budget (R-T5c).
     */
    private static final class Recorder {

        final WorkingCopyDb db;
        final List<Step> forward = new ArrayList<>();
        final List<Step> inverse = new ArrayList<>();
        private long budget;

        Recorder(WorkingCopyDb db, long budget) {
            this.db = db;
            this.budget = budget;
        }

        private void spend(Step forwardStep, Step inverseStep) {
            budget -= estimate(forwardStep) + estimate(inverseStep);
            if (budget < 0) {
                throw new IllegalArgumentException("The batch changes too much text at once; split it");
            }
        }

        private static void checkLength(Line l) {
            if (l.text().length() > MAX_LINE) {
                throw new IllegalArgumentException("A line has at most " + MAX_LINE + " characters");
            }
            // the cap of an upload holds for every edit too, so reads stay bounded by it
            if (l.cells().size() > CopyText.MAX_CELLS) {
                throw new IllegalArgumentException("A line has at most 20,000 cells");
            }
        }

        void replace(long n, Line line) {
            checkLength(line);
            Line old = db.line(n).orElseThrow(() -> new IllegalArgumentException("There is no line " + n));
            if (old.cells().equals(line.cells()) && old.eol().equals(line.eol())
                && old.section().equals(line.section())) {
                return;
            }
            Step fwd = new Step("REPLACE", n, 1, List.of(StoredLine.of(line)));
            Step inv = new Step("REPLACE", n, 1, List.of(StoredLine.of(old)));
            spend(fwd, inv);
            db.replaceLine(n, line);
            forward.add(fwd);
            inverse.add(inv);
        }

        void insert(long before, List<Line> lines) {
            lines.forEach(Recorder::checkLength);
            Step fwd = new Step("INSERT", before, lines.size(), lines.stream().map(StoredLine::of).toList());
            Step inv = new Step("DELETE", before, lines.size(), List.of());
            spend(fwd, inv);
            db.insertLines(before, lines);
            forward.add(fwd);
            inverse.add(inv);
        }

        void delete(long from, int count) {
            List<Line> old = db.lines(from, count);
            Step fwd = new Step("DELETE", from, count, List.of());
            Step inv = new Step("INSERT", from, count, old.stream().map(StoredLine::of).toList());
            spend(fwd, inv);
            db.deleteLines(from, count);
            forward.add(fwd);
            inverse.add(inv);
        }
    }

    /** Fixed JSON of a step: field names, the op name, two numbers of up to 20 digits, brackets, comma; rounded up. */
    private static final long STEP_COST = 128;
    /** Fixed JSON of a line image: field names, brackets, quotes, commas; rounded up. */
    private static final long LINE_COST = 64;

    /** An upper bound of the JSON that {@code toJson} emits for the step. */
    static long estimate(Step step) {
        long n = STEP_COST;
        for (StoredLine l : step.lines()) {
            n += LINE_COST + jsonSize(l.section()) + jsonSize(l.eol());
            for (String c : l.cells()) {
                n += jsonSize(c) + 3;
            }
        }
        return n;
    }

    /** Upper bound of the JSON size of a string: control characters take 6, quote and backslash 2. */
    static long jsonSize(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c < 0x20 ? 6 : (c == '"' || c == '\\') ? 2 : 1;
        }
        return n;
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

    static List<Step> fromJson(String json) {
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
            case MergeColumns o -> mergeColumns(r, o);
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
        Line after = rebuild(n, cells, l.eol());
        noNewRepeat(l, after);
        r.replace(n, after);
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
        Line inserted = CopyText.line(before, text, eol);
        noNewRepeat(null, inserted);
        r.insert(before, List.of(inserted));
    }

    private void setRaw(Recorder r, long n, String text) {
        Line l = lineAt(r.db, n);
        rawLine(text);
        Line after = CopyText.line(n, text, l.eol());
        noNewRepeat(l, after);
        r.replace(n, after);
    }

    private void moveRow(Recorder r, long line, long before) {
        long count = r.db.lineCount();
        Line moved = lineAt(r.db, line);
        if (before < 1 || before > count + 1) {
            throw new IllegalArgumentException("Cannot move before line " + before);
        }
        long dest = before > line ? before - 1 : before;
        if (dest == line) {
            return;
        }
        String terminator = terminator(r.db);
        boolean unterminated = lineAt(r.db, count).eol().isEmpty();
        // the line moves with its own ending; only an unterminated last line needs a fix
        String eol = moved.eol();
        if (unterminated && line == count) {
            eol = terminator;
        } else if (unterminated && dest == count) {
            eol = "";
        }
        r.delete(line, 1);
        r.insert(dest, List.of(new Line(dest, moved.section(), moved.cells(), eol)));
        if (unterminated && line == count) {
            Line last = lineAt(r.db, count);
            r.replace(count, new Line(count, last.section(), last.cells(), ""));
        } else if (unterminated && dest == count) {
            Line prev = lineAt(r.db, count - 1);
            r.replace(count - 1, new Line(count - 1, prev.section(), prev.cells(), terminator));
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
        Line joined = CopyText.line(n - 1, text, cur.eol());
        noNewRepeat(prev, joined);
        r.replace(n - 1, joined);
        r.delete(n, 1);
    }

    private void splitCells(Recorder r, long n) {
        Line l = lineAt(r.db, n);
        if (l.cells().size() < 2) {
            throw new IllegalArgumentException("Line " + n + " has no cell after the prefix");
        }
        String c = l.cells().get(1);
        int first = 0;
        while (first < c.length() && c.charAt(first) == ' ') {
            first++;
        }
        int start = c.indexOf(' ', first);
        if (first == c.length() || start < 0) {
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
        Line after = rebuild(n, cells, l.eol());
        noNewRepeat(l, after);
        r.replace(n, after);
    }

    /**
     * Refuses a header line (SMH, SFH or SEH) that would hold a column name more often than the line did before,
     * when that is more than once: no edit creates a repeated column name, while repeats a loaded file already has
     * stay editable so they can be resolved. Empty cells name no column.
     *
     * @param before the line as it was, or {@code null} for a new line; a line that was not a header counts as none
     */
    private static void noNewRepeat(@Nullable Line before, Line after) {
        if (!ROW_SECTION.containsKey(after.section())) {
            return;
        }
        Map<String, Integer> had = names(before != null && ROW_SECTION.containsKey(before.section())
            ? before : null);
        Map<String, Integer> has = names(after);
        for (int i = 1; i < after.cells().size(); i++) {
            String name = after.cells().get(i);
            int count = has.getOrDefault(name, 0);
            if (count > 1 && count > had.getOrDefault(name, 0)) {
                throw new IllegalArgumentException(after.section() + " would have the column " + name
                    + " more than once; a column name can be used once");
            }
        }
    }

    /** How often each non-empty column name occurs in a header line. */
    private static Map<String, Integer> names(@Nullable Line header) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        if (header != null) {
            header.cells().stream().skip(1).filter(c -> !c.isEmpty()).forEach(c -> counts.merge(c, 1, Integer::sum));
        }
        return counts;
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

    /** The cell index of the column: {@code position} if given (it must hold the name), else the first match. */
    private static int columnIndex(Line header, String name, Integer position) {
        if (position == null) {
            return columnIndex(header, name);
        }
        return positionOf(header, name, position);
    }

    private static int positionOf(Line header, String name, int position) {
        if (name == null) {
            throw new IllegalArgumentException("A column name is required");
        }
        if (position < 1 || position >= header.cells().size()) {
            throw new IllegalArgumentException("A column position must be between 1 and " + (header.cells().size() - 1));
        }
        if (!header.cells().get(position).equals(name)) {
            throw new IllegalArgumentException("Column " + position + " of " + header.section() + " is not " + name);
        }
        return position;
    }

    /** Refuses a name that a column of the header other than {@code except} (a cell index, or -1) already has. */
    private static void requireUnique(Line header, String name, int except) {
        for (int i = 1; i < header.cells().size(); i++) {
            if (i != except && header.cells().get(i).equals(name)) {
                throw new IllegalArgumentException(header.section() + " already has a column " + name
                    + "; a column name can be used once");
            }
        }
    }

    private void insertColumn(Recorder r, InsertColumn o) {
        String rows = rowSection(o.header());
        name(o.name());
        cell(o.fill());
        Line header = firstOf(r.db, o.header());
        if (o.position() < 1 || o.position() > header.cells().size()) {
            throw new IllegalArgumentException("A column position must be between 1 and " + header.cells().size());
        }
        requireUnique(header, o.name(), -1);
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
        int idx = columnIndex(header, o.name(), o.position());
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
        int idx = columnIndex(header, o.name(), o.position());
        if (!o.newName().equals(o.name())) {
            requireUnique(header, o.newName(), idx);
        }
        List<String> hc = new ArrayList<>(header.cells());
        hc.set(idx, o.newName());
        r.replace(header.number(), rebuild(header.number(), hc, header.eol()));
    }

    /** Empty and {@code null} cells hold no value. */
    static boolean noValue(String cell) {
        return cell.isEmpty() || cell.equals("null");
    }

    private void mergeColumns(Recorder r, MergeColumns o) {
        String rows = rowSection(o.header());
        Line header = firstOf(r.db, o.header());
        int keep = positionOf(header, o.name(), o.keep());
        List<Integer> given = o.others();
        if (given == null || given.isEmpty()) {
            throw new IllegalArgumentException("A merge needs at least one other column");
        }
        // checked first, so the work below is bounded by the header
        int width = header.cells().size();
        if (given.size() > width - 2) {
            throw new IllegalArgumentException("A merge names at most " + (width - 2) + " other columns");
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int[] others = new int[given.size()];
        boolean[] removed = new boolean[width];
        for (int i = 0; i < others.length; i++) {
            Integer other = given.get(i);
            if (other == null) {
                throw new IllegalArgumentException("A column position is required");
            }
            positionOf(header, o.name(), other);
            if (other == keep) {
                throw new IllegalArgumentException("The kept column cannot also be merged into itself");
            }
            if (!seen.add(other)) {
                throw new IllegalArgumentException("Column " + other + " is named twice in the merge");
            }
            others[i] = other;
            removed[other] = true;
        }
        r.replace(header.number(), rebuild(header.number(), without(header.cells(), removed), header.eol()));
        forEachRow(r, rows, l -> {
            List<String> cells = l.cells();
            String kept = keep < cells.size() ? cells.get(keep) : "";
            String value = kept;
            if (noValue(kept)) {
                for (int other : others) {
                    String candidate = other < cells.size() ? cells.get(other) : "";
                    if (!noValue(candidate)) {
                        value = candidate;
                        break;
                    }
                }
            }
            if (!value.equals(kept)) {
                cells = new ArrayList<>(cells);
                while (cells.size() <= keep) {
                    cells.add("");
                }
                cells.set(keep, value);
            }
            return without(cells, removed);
        });
    }

    /** The cells whose index is not marked, in one pass; indexes past the mask are kept. */
    private static List<String> without(List<String> cells, boolean[] removed) {
        List<String> out = new ArrayList<>(cells.size());
        for (int i = 0; i < cells.size(); i++) {
            if (i >= removed.length || !removed[i]) {
                out.add(cells.get(i));
            }
        }
        return out;
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
