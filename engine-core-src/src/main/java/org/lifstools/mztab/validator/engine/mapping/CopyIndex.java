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
package org.lifstools.mztab.validator.engine.mapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lifstools.mztab.submission.mapping.Render;

/**
 * What a working copy's text already has: its metadata lines and sections, the indexes of its elements and their
 * names, and the indexes handed out to new elements so far. Lines are numbered from 1. The text is read as text,
 * line by line; it need not be valid mzTab-M.
 *
 * <p>An element's name is the value of {@code element[n]} (e.g. {@code sample[1]  P001}), for a contact the value
 * of {@code contact[n]-name}, and for a run the name of the assay whose {@code -ms_run_ref} names it. (On the
 * mapping's side, {@link MappingApplier} pairs runs with the names of {@code ref-to ms_run} by order of first
 * reference.) Names are
 * compared as cell text ({@link Render#text}).
 */
final class CopyIndex {

    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\r|\n");
    private static final Pattern INDEXED = Pattern.compile("^([A-Za-z_]+)\\[(\\d{1,9})\\](.*)$", Pattern.DOTALL);
    private static final Pattern PREFIX = Pattern.compile("^[A-Za-z_]+");
    private static final Pattern RUN_REF = Pattern.compile("^ms_run\\[(\\d{1,9})\\]");

    private final List<String> lines = new ArrayList<>();
    private final List<String> endings = new ArrayList<>();
    private final Map<String, Integer> metadataLines = new HashMap<>();
    private final Map<String, Integer> lastByPrefix = new HashMap<>();
    private final Set<String> sections = new HashSet<>();
    private final Map<String, Integer> highest = new HashMap<>();
    private final Map<String, Integer> byName = new HashMap<>();
    private final Map<Integer, Integer> runOfAssay = new HashMap<>();
    private final Set<String> cvLabels = new LinkedHashSet<>();
    private final Map<String, Integer> allocated = new HashMap<>();
    private int lastMetadataLine;

    CopyIndex(String text) {
        Matcher m = LINE_BREAK.matcher(text);
        int from = 0;
        while (m.find()) {
            lines.add(text.substring(from, m.start()));
            endings.add(m.group());
            from = m.end();
        }
        if (from < text.length()) {
            lines.add(text.substring(from));
            endings.add("");
        }
        for (int n = 1; n <= lines.size(); n++) {
            read(n, lines.get(n - 1).split("\t", -1));
        }
    }

    private void read(int n, String[] cells) {
        sections.add(cells[0]);
        if (!"MTD".equals(cells[0])) {
            return;
        }
        lastMetadataLine = n;
        if (cells.length < 2) {
            return;
        }
        String key = cells[1];
        String value = cells.length > 2 ? cells[2] : "";
        metadataLines.putIfAbsent(key, n);
        Matcher prefix = PREFIX.matcher(key);
        if (prefix.find()) {
            lastByPrefix.put(prefix.group(), n);
        }
        Matcher indexed = INDEXED.matcher(key);
        if (!indexed.matches()) {
            return;
        }
        String element = indexed.group(1);
        int index = Integer.parseInt(indexed.group(2));
        String rest = indexed.group(3);
        highest.merge(element, index, Math::max);
        if ("contact".equals(element) ? "-name".equals(rest) : rest.isEmpty()) {
            byName.putIfAbsent(nameKey(element, Render.text(value)), index);
        } else if ("assay".equals(element) && "-ms_run_ref".equals(rest)) {
            Matcher run = RUN_REF.matcher(value.strip());
            if (run.find()) {
                runOfAssay.putIfAbsent(index, Integer.parseInt(run.group(1)));
            }
        } else if ("cv".equals(element) && "-label".equals(rest)) {
            cvLabels.add(value.strip());
        }
    }

    private static String nameKey(String element, String name) {
        return element + "\u0000" + name;
    }

    /** The copy's index of the element {@code element} called {@code name}, or 0 when the copy has none. */
    int indexOf(String element, String name) {
        String n = Render.text(name);
        if ("ms_run".equals(element)) {
            Integer assay = byName.get(nameKey("assay", n));
            return assay == null ? 0 : runOfAssay.getOrDefault(assay, 0);
        }
        return byName.getOrDefault(nameKey(element, n), 0);
    }

    /** The next free index of {@code element}: one more than the highest the copy uses or this index handed out. */
    int next(String element) {
        return highest.getOrDefault(element, 0) + 1;
    }

    /**
     * The index of the element {@code element} called {@code name}: the copy's, else the one handed out for that name
     * before, else the next free one, which is then taken.
     */
    int allocate(String element, String name) {
        int existing = indexOf(element, name);
        if (existing > 0) {
            return existing;
        }
        return allocated.computeIfAbsent(nameKey(element, Render.text(name)), k -> reserve(element));
    }

    /** Takes the next free index of {@code element}, for an element without a name. */
    int reserve(String element) {
        int n = next(element);
        highest.put(element, n);
        return n;
    }

    /** The labels of the copy's {@code cv[n]-label} lines, in the order of the lines. */
    Set<String> cvLabels() {
        return Collections.unmodifiableSet(cvLabels);
    }

    /** The number of the first MTD line with {@code key}, or 0. */
    int metadataLine(String key) {
        return metadataLines.getOrDefault(key, 0);
    }

    /** The value (cell 2) of the first MTD line with {@code key}, or null when there is none. */
    String metadataValue(String key) {
        int n = metadataLine(key);
        if (n == 0) {
            return null;
        }
        String[] cells = lines.get(n - 1).split("\t", -1);
        return cells.length > 2 ? cells[2] : "";
    }

    /** The number of the last MTD line whose key has the {@link #prefixOf prefix} {@code prefix}, or 0. */
    int lastMetadataLine(String prefix) {
        return lastByPrefix.getOrDefault(prefix, 0);
    }

    /** The number of the last MTD line, or 0. */
    int lastMetadataLine() {
        return lastMetadataLine;
    }

    /** Whether a line starts with the cell {@code prefix} (e.g. {@code SMH}). */
    boolean hasSection(String prefix) {
        return sections.contains(prefix);
    }

    /** The number of lines. */
    int lineCount() {
        return lines.size();
    }

    /** The text of line {@code n}, without its ending. */
    String line(int n) {
        return lines.get(n - 1);
    }

    /** The ending of line {@code n}: {@code \n}, {@code \r\n}, {@code \r}, or empty for an unterminated last line. */
    String ending(int n) {
        return endings.get(n - 1);
    }

    /** The key prefix by which a new line is placed: the letters and {@code _} it starts with. */
    static String prefixOf(String key) {
        Matcher m = PREFIX.matcher(key);
        return m.find() ? m.group() : key;
    }
}
