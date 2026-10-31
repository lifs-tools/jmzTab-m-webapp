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

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the stored sections of a validation: metadata groups and pages, and
 * table pages with sorting, filtering and a column window.
 */
@Component
public class SectionReader {

    /** The metadata lines of a validation, one {@link MetadataEntry} per line. */
    public static final String METADATA_FILE = "metadata.jsonl";
    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_COLUMNS = 50;
    static final int MAX_COLUMNS = 200;
    /** Hard cap on cached tables, besides the byte budget. */
    static final int MAX_CACHED_TABLES = 16;

    private record CacheKey(UUID id, Section section) {

    }

    /** @param bytes the estimated heap size, at least 1 */
    private record Table(List<String> header, List<String[]> rows, long bytes) {

    }

    private final StorageService storage;
    private final ObjectMapper json = new ObjectMapper();
    private final long maxBytes;
    // access-ordered, so iteration starts at the least recently used table; guarded by itself
    private final LinkedHashMap<CacheKey, Table> cache = new LinkedHashMap<>(8, 0.75f, true);
    private long cachedBytes;

    @Autowired
    public SectionReader(StorageService storage, @Value("${sections.cache.max-bytes:268435456}") long maxBytes) {
        this.storage = storage;
        this.maxBytes = maxBytes;
    }

    /** The number of cached tables. */
    int cachedTables() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** The estimated bytes held by the cached tables. */
    long cachedBytes() {
        synchronized (cache) {
            return cachedBytes;
        }
    }

    private void cache(CacheKey key, Table table) {
        if (table.bytes() > maxBytes) {
            return;
        }
        synchronized (cache) {
            Table old = cache.put(key, table);
            if (old != null) {
                cachedBytes -= old.bytes();
            }
            cachedBytes += table.bytes();
            var it = cache.entrySet().iterator();
            while ((cachedBytes > maxBytes || cache.size() > MAX_CACHED_TABLES) && it.hasNext()) {
                var eldest = it.next();
                if (eldest.getKey().equals(key)) {
                    continue;
                }
                cachedBytes -= eldest.getValue().bytes();
                it.remove();
            }
        }
    }

    /** The metadata groups in file order; empty if the session has no metadata. */
    public List<MetadataGroup> metadataGroups(UUID id) {
        return groups(metadataEntries(id).orElse(List.of()));
    }

    /**
     * @param page 0-based
     * @param size 1 to 200
     * @return empty if the session has no metadata; an unknown group gives an empty page
     */
    public Optional<MetadataPage> metadata(UUID id, String group, int page, int size) {
        return metadata(id, group, page, size, null);
    }

    /**
     * @param key a key of the group: its page is returned and {@code page} is
     * ignored; a key that is not in the group is ignored
     * @see #metadata(UUID, String, int, int)
     */
    public Optional<MetadataPage> metadata(UUID id, String group, int page, int size, @Nullable String key) {
        checkPaging(page, size);
        return metadataEntries(id).map(entries -> metadataPage(entries, group, page, size, key));
    }

    /** The metadata groups of {@code entries}, in order of first appearance. */
    public static List<MetadataGroup> groups(List<MetadataEntry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MetadataEntry entry : entries) {
            counts.merge(groupOf(entry.key()), 1, Integer::sum);
        }
        List<MetadataGroup> groups = new ArrayList<>();
        counts.forEach((name, count) -> groups.add(new MetadataGroup(name, count)));
        return groups;
    }

    /**
     * One page of a group of {@code entries}, as {@link #metadata(UUID, String, int, int, String)}.
     *
     * @throws IllegalArgumentException for invalid paging
     */
    public static MetadataPage metadataPage(List<MetadataEntry> entries, String group, int page, int size,
        @Nullable String key) {
        checkPaging(page, size);
        List<MetadataEntry> inGroup = entries.stream().filter(e -> groupOf(e.key()).equals(group)).toList();
        int keyIndex = -1;
        for (int i = 0; key != null && i < inGroup.size(); i++) {
            if (inGroup.get(i).key().equals(key)) {
                keyIndex = i;
                break;
            }
        }
        int shown = keyIndex < 0 ? page : keyIndex / size;
        long from = (long) shown * size;
        List<MetadataEntry> slice = from >= inGroup.size() ? List.of()
            : inGroup.subList((int) from, (int) Math.min(inGroup.size(), from + size));
        return new MetadataPage(group, shown, size, inGroup.size(), slice);
    }

    /**
     * @return empty if the session has no such section
     * @throws IllegalArgumentException for an unknown column, more than
     * {@value #MAX_COLUMNS} requested columns or invalid paging
     */
    public Optional<TablePage> table(UUID id, Section section, TableQuery query) {
        checkPaging(query.page(), query.size());
        if (query.columns().size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("At most " + MAX_COLUMNS + " columns can be requested.");
        }
        Optional<Table> loaded = load(id, section);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(tablePage(section, loaded.get().header(), loaded.get().rows(), null, query));
    }

    /**
     * One page of a table, as {@link #table(UUID, Section, TableQuery)}. A name requested again selects the next
     * column of that name, so a header with duplicate names can be shown column by column.
     *
     * @param rows every row has at least as many cells as {@code header}
     * @param lineNumbers the line of each row, for {@link TableRow#lineNumber()}; null for none
     * @throws IllegalArgumentException for an unknown column, more than
     * {@value #MAX_COLUMNS} requested columns or invalid paging
     */
    public static TablePage tablePage(Section section, List<String> header, List<String[]> rows,
        @Nullable long[] lineNumbers, TableQuery query) {
        return tablePage(section, header, rows, lineNumbers, query, null);
    }

    /**
     * One page of a table whose columns may also be named by position (a working copy). With {@code at}, the page
     * reports {@link TablePage#columnPositions()}.
     *
     * @throws IllegalArgumentException as above, and for a position outside 1 to the number of columns, a repeated
     * position, or a part given both by name and by position
     */
    public static TablePage tablePage(Section section, List<String> header, List<String[]> rows,
        @Nullable long[] lineNumbers, TableQuery query, @Nullable PositionQuery at) {
        checkPaging(query.page(), query.size());
        if (query.columns().size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("At most " + MAX_COLUMNS + " columns can be requested.");
        }
        List<Integer> positions = at == null ? null : at.positions();
        Integer sortPosition = at == null ? null : at.sortPosition();
        if (positions != null && !query.columns().isEmpty()) {
            throw new IllegalArgumentException("columns and positions cannot be combined.");
        }
        if (sortPosition != null && query.sort() != null) {
            throw new IllegalArgumentException("sort and sortPosition cannot be combined.");
        }
        int[] columns;
        if (positions != null) {
            columns = columnsAt(header, positions);
        } else if (query.columns().isEmpty()) {
            columns = java.util.stream.IntStream.range(0, Math.min(DEFAULT_COLUMNS, header.size())).toArray();
        } else {
            columns = requestedColumns(header, query.columns());
        }
        int sortColumn = sortPosition != null ? indexAt(header, sortPosition, "sortPosition")
            : query.sort() == null ? -1 : columnIndex(header, query.sort());
        List<ColumnFilter> allFilters = new ArrayList<>(query.filters());
        query.rawFilters().forEach(raw -> allFilters.add(resolveFilter(header, raw)));
        List<Integer> filterIndexes = new ArrayList<>();
        allFilters.forEach(f -> filterIndexes.add(columnIndex(header, f.column())));
        List<String> needleList = new ArrayList<>();
        allFilters.forEach(f -> needleList.add(f.contains() == null ? "" : f.contains()));
        for (String raw : at == null ? List.<String>of() : at.filters()) {
            int colon = raw == null ? -1 : raw.indexOf(':');
            if (colon < 1 || !raw.substring(0, colon).chars().allMatch(Character::isDigit) || colon > 9) {
                throw new IllegalArgumentException("positionFilter must have the form POSITION:text.");
            }
            filterIndexes.add(indexAt(header, Integer.parseInt(raw.substring(0, colon)), "positionFilter"));
            needleList.add(raw.substring(colon + 1));
        }
        int[] filterColumns = filterIndexes.stream().mapToInt(Integer::intValue).toArray();
        String[] needles = needleList.stream().map(n -> n.toLowerCase(Locale.ROOT)).toArray(String[]::new);

        List<Integer> matching = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (matches(rows.get(i), filterColumns, needles)) {
                matching.add(i);
            }
        }
        if (sortColumn >= 0) {
            sort(matching, rows, sortColumn, query.descending());
        }
        long from = (long) query.page() * query.size();
        List<TableRow> page = new ArrayList<>();
        for (long i = from; i < matching.size() && i < from + query.size(); i++) {
            int index = matching.get((int) i);
            String[] row = rows.get(index);
            List<String> cells = new ArrayList<>(columns.length);
            for (int column : columns) {
                cells.add(row[column]);
            }
            page.add(new TableRow(index, cells, lineNumbers == null ? null : lineNumbers[index]));
        }
        List<String> returned = Arrays.stream(columns).mapToObj(header::get).toList();
        List<Integer> cellIndexes = at != null ? Arrays.stream(columns).map(c -> c + 1).boxed().toList() : null;
        return new TablePage(section, query.page(), query.size(), matching.size(), header, returned, page,
            cellIndexes);
    }

    /** The header index of each requested column; the n-th request of a name selects its n-th column. */
    private static int[] requestedColumns(List<String> header, List<String> requested) {
        int[] columns = new int[requested.size()];
        java.util.Map<String, Integer> next = new java.util.HashMap<>();
        for (int i = 0; i < requested.size(); i++) {
            String name = requested.get(i);
            int first = columnIndex(header, name);
            int from = next.getOrDefault(name, first);
            int index = from;
            while (index < header.size() && !header.get(index).equals(name)) {
                index++;
            }
            // more requests than columns of the name: the first again
            columns[i] = index < header.size() ? index : first;
            next.put(name, columns[i] + 1);
        }
        return columns;
    }

    /** The header index of each position, which must be a column of the header and not repeat. */
    private static int[] columnsAt(List<String> header, List<Integer> positions) {
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("positions must name at least one column.");
        }
        if (positions.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("At most " + MAX_COLUMNS + " columns can be requested.");
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int[] columns = new int[positions.size()];
        for (int i = 0; i < columns.length; i++) {
            Integer p = positions.get(i);
            if (p != null && !seen.add(p)) {
                throw new IllegalArgumentException("positions must not repeat a column (" + p + ").");
            }
            columns[i] = indexAt(header, p, "positions");
        }
        return columns;
    }

    /** The header index of a cell index (the prefix is cell 0, so the first column is 1). */
    private static int indexAt(List<String> header, @Nullable Integer position, String name) {
        if (position == null || position < 1 || position > header.size()) {
            throw new IllegalArgumentException(name + " must be between 1 and " + header.size() + ".");
        }
        return position - 1;
    }

    /** Splits at the first {@code :} whose prefix is an exact column name; column names may contain {@code :}. */
    static ColumnFilter resolveFilter(List<String> header, String raw) {
        for (int colon = raw.indexOf(':'); colon >= 0; colon = raw.indexOf(':', colon + 1)) {
            if (colon > 0 && header.contains(raw.substring(0, colon))) {
                return new ColumnFilter(raw.substring(0, colon), raw.substring(colon + 1));
            }
        }
        int first = raw.indexOf(':');
        throw new IllegalArgumentException("Unknown column '" + (first < 0 ? raw : raw.substring(0, first)) + "'");
    }

    private static boolean matches(String[] row, int[] filterColumns, String[] needles) {
        for (int f = 0; f < filterColumns.length; f++) {
            if (!row[filterColumns[f]].toLowerCase(Locale.ROOT).contains(needles[f])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Numbers sort numerically among themselves, other cells as text, numbers
     * before text; {@code "null"} and empty cells come last in both directions.
     */
    private static void sort(List<Integer> matching, List<String[]> rows, int column, boolean descending) {
        Object[] keys = new Object[rows.size()];
        for (int index : matching) {
            keys[index] = sortKey(rows.get(index)[column]);
        }
        Comparator<Integer> byValue = (a, b) -> {
            Object x = keys[a];
            Object y = keys[b];
            if (x == null || y == null) {
                return x == null ? (y == null ? 0 : 1) : -1;
            }
            int result;
            if (x instanceof Double dx && y instanceof Double dy) {
                result = Double.compare(dx, dy);
            } else if (x instanceof Double) {
                result = -1;
            } else if (y instanceof Double) {
                result = 1;
            } else {
                String sx = (String) x;
                String sy = (String) y;
                result = sx.compareToIgnoreCase(sy);
                if (result == 0) {
                    result = sx.compareTo(sy);
                }
            }
            return descending ? -result : result;
        };
        matching.sort(byValue);
    }

    private static Object sortKey(String cell) {
        if (cell.isEmpty() || cell.equals("null")) {
            return null;
        }
        char first = cell.charAt(0);
        char last = cell.charAt(cell.length() - 1);
        if ((Character.isDigit(first) || first == '-' || first == '+' || first == '.')
            && (Character.isDigit(last) || last == '.')) {
            try {
                return Double.parseDouble(cell);
            } catch (NumberFormatException e) {
                // text
            }
        }
        return cell;
    }

    private static int columnIndex(List<String> header, String column) {
        int index = header.indexOf(column);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown column '" + column + "'");
        }
        return index;
    }

    /**
     * @throws IllegalArgumentException for a negative page or a size outside 1 to {@value #MAX_PAGE_SIZE}
     */
    public static void checkPaging(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("The page must not be negative.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("The page size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
    }

    private static String groupOf(String key) {
        int bracket = key.indexOf('[');
        return bracket < 0 ? "general" : key.substring(0, bracket);
    }

    private Optional<List<MetadataEntry>> metadataEntries(UUID id) {
        Optional<InputStream> stream = storage.read(id, SLOT.SECTIONS, METADATA_FILE);
        if (stream.isEmpty()) {
            return Optional.empty();
        }
        try (InputStream in = stream.get(); MappingIterator<MetadataEntry> it = json
            .readerFor(MetadataEntry.class).readValues(in)) {
            return Optional.of(it.readAll());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the metadata of validation " + id, e);
        }
    }

    /** The parsed table; a session without the section file serves nothing, cached or not. */
    private Optional<Table> load(UUID id, Section section) {
        Optional<InputStream> stream = storage.read(id, SLOT.SECTIONS, section.fileName());
        if (stream.isEmpty()) {
            return Optional.empty();
        }
        CacheKey key = new CacheKey(id, section);
        try (InputStream in = stream.get()) {
            synchronized (cache) {
                Table cached = cache.get(key);
                if (cached != null) {
                    return Optional.of(cached);
                }
            }
            Table table = parse(in);
            cache(key, table);
            return Optional.of(table);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the " + section + " of validation " + id, e);
        }
    }

    /** Shares equal cells; {@code bytes[0]} grows by the estimated size of each new distinct string. */
    private static String[] canonical(String[] cells, Map<String, String> canonical, long[] bytes) {
        // row: array header plus references
        bytes[0] += 16 + 8L * cells.length;
        for (int i = 0; i < cells.length; i++) {
            String shared = canonical.putIfAbsent(cells[i], cells[i]);
            if (shared != null) {
                cells[i] = shared;
            } else {
                bytes[0] += 2L * cells[i].length() + 40;
            }
        }
        return cells;
    }

    private Table parse(InputStream in) throws IOException {
        // one JSON array per line; a MappingIterator would unwrap a root-level array
        BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        // repeated cells ("null", "", ...) share one String per table
        Map<String, String> canonical = new HashMap<>();
        long[] bytes = new long[1];
        List<String> header = Arrays.asList(canonical(json.readValue(lines.readLine(), String[].class), canonical,
            bytes));
        List<String[]> rows = new ArrayList<>();
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            rows.add(canonical(json.readValue(line, String[].class), canonical, bytes));
        }
        return new Table(header, rows, Math.max(1, bytes[0]));
    }
}
