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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.lifstools.mztab.validator.webapp.sections.MetadataEntry;
import org.lifstools.mztab.validator.webapp.sections.MetadataGroup;
import org.lifstools.mztab.validator.webapp.sections.MetadataPage;
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
 * not a column. A row shorter than the header shows empty cells, cells past
 * the header are not shown (the lines view shows them).
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
     * @throws IllegalArgumentException for an unknown column, too many columns or invalid paging
     */
    public Optional<TablePage> table(UUID id, Section section, TableQuery query) {
        SectionReader.checkPaging(query.page(), query.size());
        return copies.guard(id, () -> table(copies.db(id), section, query));
    }

    private static Optional<TablePage> table(WorkingCopyDb db, Section section, TableQuery query) {
        String header = HEADER.get(section);
        List<Line> lines = db.linesOf(header, section.name());
        List<String> columns = null;
        List<String[]> rows = new ArrayList<>();
        List<Long> numbers = new ArrayList<>();
        for (Line l : lines) {
            List<String> cells = l.cells().subList(1, l.cells().size());
            if (l.section().equals(header)) {
                if (columns == null) {
                    columns = cells;
                }
            } else {
                rows.add(cells.toArray(String[]::new));
                numbers.add(l.number());
            }
        }
        if (columns == null) {
            return Optional.empty();
        }
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (row.length < columns.size()) {
                String[] padded = Arrays.copyOf(row, columns.size());
                Arrays.fill(padded, row.length, padded.length, "");
                rows.set(i, padded);
            }
        }
        long[] lineNumbers = numbers.stream().mapToLong(Long::longValue).toArray();
        return Optional.of(SectionReader.tablePage(section, columns, rows, lineNumbers, query, true));
    }

    /**
     * The column names that the first line of a header holds more than once, in the order of their first column;
     * empty header cells name no column. Only the tab layout is used: a value is any cell that is neither empty
     * nor {@code null}.
     *
     * @param header SMH, SFH or SEH
     * @return empty if the header has no duplicate names or the copy has no such header
     * @throws IllegalArgumentException for another header
     */
    public List<DuplicateColumn> duplicates(UUID id, String header) {
        String rows = header == null ? null : ROWS.get(header);
        if (rows == null) {
            throw new IllegalArgumentException("header must be SMH, SFH or SEH.");
        }
        return copies.guard(id, () -> duplicates(copies.db(id).linesOf(header, rows), header));
    }

    private static List<DuplicateColumn> duplicates(List<Line> lines, String header) {
        Line head = lines.stream().filter(l -> l.section().equals(header)).findFirst().orElse(null);
        if (head == null) {
            return List.of();
        }
        Map<String, List<Integer>> byName = new java.util.LinkedHashMap<>();
        for (int i = 1; i < head.cells().size(); i++) {
            // empty cells (often trailing tabs) name no column
            if (!head.cells().get(i).isEmpty()) {
                byName.computeIfAbsent(head.cells().get(i), k -> new ArrayList<>()).add(i);
            }
        }
        List<DuplicateColumn> found = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byName.entrySet()) {
            List<Integer> positions = e.getValue();
            if (positions.size() < 2) {
                continue;
            }
            long conflicting = 0;
            List<DuplicateColumn.Sample> samples = new ArrayList<>();
            for (Line l : lines) {
                if (l.section().equals(header)) {
                    continue;
                }
                List<String> values = new ArrayList<>(positions.size());
                java.util.Set<String> distinct = new java.util.HashSet<>();
                for (int p : positions) {
                    String v = p < l.cells().size() ? l.cells().get(p) : "";
                    values.add(v);
                    if (!WorkingCopyEditor.noValue(v)) {
                        distinct.add(v);
                    }
                }
                if (distinct.size() >= 2) {
                    conflicting++;
                    if (samples.size() < MAX_DUPLICATE_SAMPLES) {
                        samples.add(new DuplicateColumn.Sample(l.number(), values));
                    }
                }
            }
            found.add(new DuplicateColumn(e.getKey(), List.copyOf(positions), conflicting, samples));
        }
        return found;
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
        for (Line l : db.linesOf("MTD", "MTD")) {
            List<String> c = l.cells();
            entries.add(new MetadataEntry((int) l.number(), c.size() > 1 ? c.get(1) : "", c.size() > 2 ? c.get(2) : ""));
        }
        return entries;
    }
}
