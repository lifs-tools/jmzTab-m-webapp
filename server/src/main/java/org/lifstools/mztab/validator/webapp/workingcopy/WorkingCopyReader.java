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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.lifstools.mztab.validator.webapp.sections.MetadataEntry;
import org.lifstools.mztab.validator.webapp.sections.MetadataGroup;
import org.lifstools.mztab.validator.webapp.sections.MetadataPage;
import org.lifstools.mztab.validator.webapp.sections.PositionQuery;
import org.lifstools.mztab.validator.webapp.sections.Section;
import org.lifstools.mztab.validator.webapp.sections.SectionReader;
import org.lifstools.mztab.validator.webapp.sections.TablePage;
import org.lifstools.mztab.validator.webapp.sections.TableQuery;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads a working copy as the result page reads a validation's sections, with
 * the same DTOs and paging rules as {@link SectionReader}, but from the copy's
 * current text: only the line prefix and the tab layout are used, nothing is
 * read as mzTab-M.
 *
 * <ul>
 * <li>Metadata: every {@code MTD} line; the key is cell 1, the value cell 2
 * (empty if missing). {@link MetadataEntry#lineNumber()} is the line in the
 * copy.</li>
 * <li>Tables: the first {@code SMH}/{@code SFH}/{@code SEH} line is the
 * header, every {@code SML}/{@code SMF}/{@code SME} line a row; the prefix is
 * not a column. A row shorter than the header shows empty cells (read as empty
 * where they are missing, the row is not padded), cells past the header are
 * not shown (the lines view shows them). The copy's caps
 * ({@link CopyText#MAX_LINES} lines, {@link CopyText#MAX_CELLS} cells a line)
 * bound every read.
 * {@link org.lifstools.mztab.validator.webapp.sections.TableRow#lineNumber()}
 * is the row's line.</li>
 * </ul>
 * Each read is a single statement, so it sees one revision of the text.
 */
@Component
public class WorkingCopyReader {

    /** The most lines one {@link #lines} call returns. */
    public static final int MAX_LINES = 1000;

    /** The most conflicting rows {@link #duplicates} returns per column name. */
    public static final int MAX_DUPLICATE_SAMPLES = 20;

    private static final Map<String, String> ROWS = Map.of("SMH", "SML", "SFH", "SMF", "SEH", "SME");

    private static final Map<Section, String> HEADER = Map.of(Section.SML, "SMH", Section.SMF, "SFH",
        Section.SME, "SEH");

    private final WorkingCopyService copies;

    public WorkingCopyReader(WorkingCopyService copies) {
        this.copies = copies;
    }

    public List<MetadataGroup> metadataGroups(UUID id) {
        return copies.guard(id, () -> SectionReader.groups(metadataEntries(copies.db(id))));
    }

    /**
     * @param page 0-based
     * @param size 1 to 200
     * @param key a key of the group: its page is returned and {@code page} is ignored
     * @throws IllegalArgumentException for invalid paging
     */
    public MetadataPage metadata(UUID id, String group, int page, int size, @Nullable String key) {
        // checked before reading, so a bad request costs nothing
        SectionReader.checkPaging(page, size);
        return copies.guard(id,
            () -> SectionReader.metadataPage(metadataEntries(copies.db(id)), group, page, size, key));
    }

    /**
     * @return empty if the copy has no header line for the section
     * @throws IllegalArgumentException for an unknown column, too many columns, a position outside the header, a
     * part given both by name and by position, or invalid paging
     */
    public Optional<TablePage> table(UUID id, Section section, TableQuery query) {
        return table(id, section, query, PositionQuery.NONE);
    }

    /** One page whose columns, sort column or filters may be named by position (cell index). */
    public Optional<TablePage> table(UUID id, Section section, TableQuery query, PositionQuery at) {
        SectionReader.checkPaging(query.page(), query.size());
        return copies.guard(id, () -> table(copies.db(id), section, query, at));
    }

    private static Optional<TablePage> table(WorkingCopyDb db, Section section, TableQuery query,
        PositionQuery at) {
        String header = HEADER.get(section);
        List<List<String>> columns = new ArrayList<>(1);
        List<String[]> rows = new ArrayList<>();
        List<Long> numbers = new ArrayList<>();
        // streamed: each row is kept once, as its own cells; rows are not padded to the header
        db.forEachLineOf(header, section.name(), l -> {
            List<String> cells = l.cells();
            if (l.section().equals(header)) {
                if (columns.isEmpty()) {
                    columns.add(cells.subList(1, cells.size()));
                }
            } else {
                String[] row = new String[cells.size() - 1];
                for (int i = 1; i < cells.size(); i++) {
                    row[i - 1] = cells.get(i);
                }
                rows.add(row);
                numbers.add(l.number());
            }
        });
        if (columns.isEmpty()) {
            return Optional.empty();
        }
        long[] lineNumbers = numbers.stream().mapToLong(Long::longValue).toArray();
        return Optional.of(SectionReader.tablePage(section, columns.get(0), rows, lineNumbers, query, at));
    }

    /**
     * The column names that the first line of a header holds more than once, in the order of their first column;
     * empty header cells name no column. Only the tab layout is used: a value is any cell that is neither empty
     * nor {@code null}.
     *
     * @param header SMH, SFH or SEH, in any case (as the tables endpoint accepts sml)
     * @return empty if the header has no duplicate names or the copy has no such header
     * @throws IllegalArgumentException for another header
     */
    public List<DuplicateColumn> duplicates(UUID id, String header) {
        String name = header == null ? null : header.toUpperCase(Locale.ROOT);
        String rows = name == null ? null : ROWS.get(name);
        if (rows == null) {
            throw new IllegalArgumentException("header must be SMH, SFH or SEH.");
        }
        return copies.guard(id, () -> {
            Collector c = new Collector(name);
            // one statement, so one revision of the text
            copies.db(id).forEachLineOf(name, rows, c::accept);
            return c.result();
        });
    }

    /** Package-private for tests: the duplicates of the first {@code header} line among {@code lines}. */
    static List<DuplicateColumn> duplicates(String header, Iterable<Line> lines) {
        Collector c = new Collector(header);
        lines.forEach(c::accept);
        return c.result();
    }

    /** Scans the rows once the first header line is known; rows before it (a malformed file) wait for it. */
    private static final class Collector {

        private final String header;
        private final List<Line> early = new ArrayList<>(0);
        private Scan scan;

        Collector(String header) {
            this.header = header;
        }

        void accept(Line l) {
            if (l.section().equals(header)) {
                if (scan == null) {
                    scan = new Scan(l);
                    early.forEach(scan::row);
                    early.clear();
                }
            } else if (scan == null) {
                early.add(l);
            } else if (!scan.names.isEmpty()) {
                scan.row(l);
            }
        }

        List<DuplicateColumn> result() {
            return scan == null ? List.of() : scan.result();
        }
    }

    /**
     * One pass over the rows for every repeated name of a header. A row costs at most its own cells: the
     * positions of a name are ascending, so the scan of a row stops at its end, and a row allocates nothing
     * unless it becomes a sample.
     */
    private static final class Scan {

        final List<String> names = new ArrayList<>();
        final List<int[]> positions = new ArrayList<>();
        final long[] conflicting;
        final List<List<DuplicateColumn.Sample>> samples = new ArrayList<>();

        Scan(Line head) {
            Map<String, List<Integer>> byName = new java.util.LinkedHashMap<>();
            for (int i = 1; i < head.cells().size(); i++) {
                // empty cells (often trailing tabs) name no column
                if (!head.cells().get(i).isEmpty()) {
                    byName.computeIfAbsent(head.cells().get(i), k -> new ArrayList<>()).add(i);
                }
            }
            byName.forEach((name, at) -> {
                if (at.size() >= 2) {
                    names.add(name);
                    positions.add(at.stream().mapToInt(Integer::intValue).toArray());
                    samples.add(new ArrayList<>());
                }
            });
            conflicting = new long[names.size()];
        }

        void row(Line l) {
            List<String> cells = l.cells();
            for (int n = 0; n < names.size(); n++) {
                int[] at = positions.get(n);
                String first = null;
                boolean differ = false;
                for (int p : at) {
                    if (p >= cells.size()) {
                        // a missing cell holds no value, nor do the later ones
                        break;
                    }
                    String v = cells.get(p);
                    if (WorkingCopyEditor.noValue(v)) {
                        continue;
                    }
                    if (first == null) {
                        first = v;
                    } else if (!first.equals(v)) {
                        differ = true;
                        break;
                    }
                }
                if (differ) {
                    conflicting[n]++;
                    if (samples.get(n).size() < MAX_DUPLICATE_SAMPLES) {
                        List<String> values = new ArrayList<>(at.length);
                        for (int p : at) {
                            values.add(p < cells.size() ? cells.get(p) : "");
                        }
                        samples.get(n).add(new DuplicateColumn.Sample(l.number(), values));
                    }
                }
            }
        }

        List<DuplicateColumn> result() {
            List<DuplicateColumn> found = new ArrayList<>(names.size());
            for (int n = 0; n < names.size(); n++) {
                found.add(new DuplicateColumn(names.get(n), java.util.Arrays.stream(positions.get(n)).boxed().toList(),
                    conflicting[n], List.copyOf(samples.get(n))));
            }
            return found;
        }
    }

    /**
     * Raw lines by range.
     *
     * @param from 1 to the line count + 1 (which gives no lines)
     * @param count 1 to {@value #MAX_LINES}
     * @throws IllegalArgumentException for a range outside these bounds
     */
    public List<Line> lines(UUID id, long from, int count) {
        if (count < 1 || count > MAX_LINES) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_LINES + ".");
        }
        if (from < 1) {
            throw new IllegalArgumentException("from must be at least 1.");
        }
        return copies.guard(id, () -> {
            WorkingCopyDb db = copies.db(id);
            long lineCount = db.lineCount();
            if (from > lineCount + 1) {
                throw new IllegalArgumentException("from must be at most " + (lineCount + 1) + ", the line count + 1.");
            }
            return db.lines(from, count);
        });
    }

    private static List<MetadataEntry> metadataEntries(WorkingCopyDb db) {
        List<MetadataEntry> entries = new ArrayList<>();
        // streamed: only the key and value of each line are kept
        db.forEachLineOf("MTD", "MTD", l -> {
            List<String> c = l.cells();
            entries.add(new MetadataEntry((int) l.number(), c.size() > 1 ? c.get(1) : "", c.size() > 2 ? c.get(2) : ""));
        });
        return entries;
    }
}
