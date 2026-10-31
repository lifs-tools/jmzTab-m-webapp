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
package org.lifstools.mztab.validator.webapp.workingcopy.quickfix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.EditBatch;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.*;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyEditor;
import org.springframework.lang.Nullable;

/**
 * The quick fixes, and the preview of one. A preview never writes to the
 * copy: the operations come from the fixes, and the changed lines are
 * computed by replaying them on the lines they name (see {@link #changes}).
 */
public final class QuickFixes {

    /** The most changed lines a preview lists. */
    public static final int MAX_CHANGES = 200;

    /** The id of the fix for a file that is not UTF-8: not a text edit, so it has no {@link QuickFix}. */
    public static final String REENCODE_LATIN1 = "reencode-latin1";

    private static final List<QuickFix> FIXES = List.of(new RemoveEmptyParameter(), new NormaliseDoi(),
        new JoinBrokenLine(), new SplitMtdLine(), new AddMandatoryColumn(), new AddMissingMetadata(),
        new RemoveUnusedCv());

    private QuickFixes() {
    }

    /** Every fix id the guidance may name, including {@link #REENCODE_LATIN1}. */
    public static Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        FIXES.forEach(f -> ids.add(f.id()));
        ids.add(REENCODE_LATIN1);
        return Set.copyOf(ids);
    }

    /** The fix with the id; empty for an unknown id and for {@link #REENCODE_LATIN1}. */
    public static Optional<QuickFix> find(String id) {
        return FIXES.stream().filter(f -> f.id().equals(id)).findFirst();
    }

    /**
     * Previews a fix.
     *
     * @param revision the revision of the copy that the messages belong to
     * @param chosen the message to fix
     * @param group the messages to fix together, or null to fix only {@code chosen}
     * @throws IllegalArgumentException if the fix does not apply to {@code chosen}, cannot
     * be applied to a group, or would need more than {@value WorkingCopyEditor#MAX_OPERATIONS}
     * operations
     */
    public static QuickFixPreview preview(QuickFix fix, long revision, ValidationMessage chosen,
        @Nullable List<ValidationMessage> group, WorkingCopyDb db) {
        List<EditOperation> own = fix.forMessage(chosen, db).orElseThrow(
            () -> new IllegalArgumentException("This quick fix does not apply to this message."));
        List<List<EditOperation>> parts = new ArrayList<>();
        if (group == null) {
            parts.add(own);
        } else {
            if (!fix.appliesToAll()) {
                throw new IllegalArgumentException("This quick fix applies to one message at a time.");
            }
            Set<List<EditOperation>> distinct = new LinkedHashSet<>();
            int operations = 0;
            for (ValidationMessage m : group) {
                Optional<List<EditOperation>> ops = fix.forMessage(m, db);
                if (ops.isPresent() && distinct.add(ops.get())) {
                    operations += ops.get().size();
                    if (operations > WorkingCopyEditor.MAX_OPERATIONS) {
                        throw new IllegalArgumentException("This fix would change more than "
                            + WorkingCopyEditor.MAX_OPERATIONS + " places at once; the file has " + group.size()
                            + " similar problems. Fix them in the tool that wrote the file.");
                    }
                }
            }
            parts.addAll(distinct);
            // from the bottom up, so that deletes and joins leave the numbers of the later operations valid
            parts.sort(Comparator.comparingLong((List<EditOperation> p) -> lineOf(p.get(0))).reversed());
        }
        List<EditOperation> all = parts.stream().flatMap(List::stream).collect(java.util.stream.Collectors.toList());
        if (group != null) {
            // edits that keep the line numbers first, then those that move lines (stable, so still bottom-up):
            // a join must not be followed by a rewrite of the line it merged into
            all.sort(Comparator.comparingInt(op -> movesLines(op) ? 1 : 0));
        }
        List<LineChange> changes = changes(all, db);
        return new QuickFixPreview(fix.id(), fix.title(), revision, parts.size(),
            changes.size() > MAX_CHANGES ? List.copyOf(changes.subList(0, MAX_CHANGES)) : changes,
            new EditBatch(revision, all));
    }

    private static boolean movesLines(EditOperation op) {
        return op instanceof DeleteLine || op instanceof JoinWithPrevious || op instanceof InsertLine
            || op instanceof MoveRow || op instanceof InsertColumn || op instanceof DeleteColumn;
    }

    private static long lineOf(EditOperation op) {
        return switch (op) {
            case DeleteLine o -> o.line();
            case SetMetadataValue o -> o.line();
            case RenameMetadataKey o -> o.line();
            case SetRawLine o -> o.line();
            case SplitCells o -> o.line();
            case JoinWithPrevious o -> o.line();
            case InsertLine o -> o.before();
            default -> 0;
        };
    }

    /**
     * The changed lines of a batch, in line order, by replaying the
     * operations on the lines they name. Line numbers are those of the copy
     * now; this holds because the fixes emit their operations so that no
     * operation moves a line that a later one names. Only the operations the
     * fixes produce are supported. All changes are returned (a column insert
     * stops reading after {@value #MAX_CHANGES} + 1 lines); the caller truncates.
     */
    static List<LineChange> changes(List<EditOperation> ops, WorkingCopyDb db) {
        Map<Long, String> original = new TreeMap<>();
        Map<Long, String> after = new TreeMap<>();
        List<LineChange> inserts = new ArrayList<>();
        List<LineChange> columns = new ArrayList<>();
        for (EditOperation op : ops) {
            switch (op) {
                case DeleteLine o -> {
                    current(o.line(), original, after, db);
                    after.put(o.line(), null);
                }
                case SetMetadataValue o -> after.put(o.line(), withCell(current(o.line(), original, after, db), 2,
                    o.value()));
                case RenameMetadataKey o -> after.put(o.line(), withCell(current(o.line(), original, after, db), 1,
                    o.key()));
                case SetRawLine o -> {
                    current(o.line(), original, after, db);
                    after.put(o.line(), o.text());
                }
                case SplitCells o -> after.put(o.line(), split(current(o.line(), original, after, db)));
                case JoinWithPrevious o -> {
                    String line = current(o.line(), original, after, db);
                    after.put(o.line() - 1, current(o.line() - 1, original, after, db) + line);
                    after.put(o.line(), null);
                }
                case InsertLine o -> inserts.add(new LineChange(o.before(), null, o.text()));
                case InsertColumn o -> columns.addAll(insertColumn(o, db));
                default -> throw new IllegalStateException("No preview for " + op);
            }
        }
        List<LineChange> out = new ArrayList<>(columns);
        out.addAll(inserts);
        for (Map.Entry<Long, String> e : after.entrySet()) {
            String before = original.get(e.getKey());
            if (!before.equals(e.getValue())) {
                out.add(new LineChange(e.getKey(), before, e.getValue()));
            }
        }
        out.sort(Comparator.comparingLong(LineChange::lineNumber));
        return out;
    }

    private static String current(long n, Map<Long, String> original, Map<Long, String> after, WorkingCopyDb db) {
        String text = original.computeIfAbsent(n,
            k -> db.line(k).orElseThrow(() -> new IllegalArgumentException("There is no line " + k)).text());
        return after.containsKey(n) ? after.get(n) : text;
    }

    private static String withCell(String line, int index, String value) {
        List<String> cells = new ArrayList<>(List.of(line.split("\t", -1)));
        while (cells.size() <= index) {
            cells.add("");
        }
        cells.set(index, value);
        return String.join("\t", cells);
    }

    /** As {@code SplitCells}: the cell after the prefix is split at its first run of spaces. */
    private static String split(String line) {
        List<String> cells = new ArrayList<>(List.of(line.split("\t", -1)));
        String c = cells.get(1);
        int first = 0;
        while (first < c.length() && c.charAt(first) == ' ') {
            first++;
        }
        int start = c.indexOf(' ', first);
        int end = start;
        while (end < c.length() && c.charAt(end) == ' ') {
            end++;
        }
        cells.set(1, c.substring(0, start));
        cells.add(2, c.substring(end));
        return String.join("\t", cells);
    }

    private static final Map<String, String> ROWS = Map.of("SMH", "SML", "SFH", "SMF", "SEH", "SME");

    /** The header and the first rows of the table; reading stops once enough changes are listed. */
    private static List<LineChange> insertColumn(InsertColumn o, WorkingCopyDb db) {
        String rows = ROWS.get(o.header());
        List<LineChange> out = new ArrayList<>();
        boolean header = false;
        Iterator<Line> it = db.allLines();
        while (it.hasNext() && out.size() <= MAX_CHANGES) {
            Line l = it.next();
            List<String> cells = new ArrayList<>(l.cells());
            if (!header && l.section().equals(o.header())) {
                header = true;
                cells.add(o.position(), o.name());
            } else if (l.section().equals(rows)) {
                while (cells.size() < o.position()) {
                    cells.add("");
                }
                cells.add(o.position(), o.fill());
            } else {
                continue;
            }
            out.add(new LineChange(l.number(), l.text(), String.join("\t", cells)));
        }
        return out;
    }
}
