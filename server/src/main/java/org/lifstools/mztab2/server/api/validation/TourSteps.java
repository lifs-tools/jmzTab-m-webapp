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
package org.lifstools.mztab2.server.api.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.lang.Nullable;

/**
 * Checks a tour against the lines of its file, textually: line prefixes and tab-separated fields, never the
 * mzTab-M semantics. Every step's excerpt must lie inside the file and its target must name an element on the
 * excerpt's lines, so the chapter and the tour cannot show something the file does not have.
 */
final class TourSteps {

    static final Set<String> CHAPTERS = Set.of("overview", "metadata", "summary", "features", "evidence",
        "references", "cv-parameters", "profile", "study-design");

    /** The data line prefix of each table tab. */
    private static final Map<String, String> ROW_PREFIX = Map.of("summary", "SML", "features", "SMF",
        "evidence", "SME");

    /** The header line prefix of each table tab. */
    private static final Map<String, String> HEADER_PREFIX = Map.of("summary", "SMH", "features", "SFH",
        "evidence", "SEH");

    private TourSteps() {
    }

    /**
     * @param fileLines the file's lines, without line terminators; line 1 is {@code fileLines.get(0)}
     * @return one message per problem, each naming its step; empty when the tour is valid
     */
    static List<String> problems(List<String> fileLines, TourDto tour) {
        List<String> problems = new ArrayList<>();
        if (blank(tour.title())) {
            problems.add("the tour has no title");
        }
        if (tour.steps() == null || tour.steps().isEmpty()) {
            problems.add("the tour has no steps");
            return problems;
        }
        List<String[]> fields = fileLines.stream().map(l -> l.split("\t", -1)).toList();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < tour.steps().size(); i++) {
            TourStepDto step = tour.steps().get(i);
            if (step == null) {
                problems.add("step " + (i + 1) + " is empty");
                continue;
            }
            String name = blank(step.id()) ? "step " + (i + 1) : "step '" + step.id() + "'";
            if (blank(step.id())) {
                problems.add(name + ": the id is blank");
            } else if (!ids.add(step.id())) {
                problems.add(name + ": the id is used by an earlier step");
            }
            if (step.chapter() == null || !CHAPTERS.contains(step.chapter())) {
                problems.add(name + ": unknown chapter '" + step.chapter() + "'");
            }
            if (blank(step.title())) {
                problems.add(name + ": the title is blank");
            }
            if (blank(step.text())) {
                problems.add(name + ": the text is blank");
            }
            List<Integer> lines = step.lines();
            if (lines == null || lines.size() != 2 || lines.get(0) == null || lines.get(1) == null
                || lines.get(0) < 1 || lines.get(0) > lines.get(1) || lines.get(1) > fileLines.size()) {
                problems.add(name + ": lines must be [from, to] with 1 <= from <= to <= " + fileLines.size()
                    + ", not " + lines);
                // the target is checked against the lines, which are unusable
                continue;
            }
            if (step.target() != null) {
                String problem = targetProblem(fields, step.target(), lines.get(0), lines.get(1));
                if (problem != null) {
                    problems.add(name + ": " + problem);
                }
            }
        }
        return problems;
    }

    @Nullable
    private static String targetProblem(List<String[]> fields, TourTargetDto target, int from, int to) {
        String tab = target.tab();
        if ("metadata".equals(tab)) {
            if (target.rowIndex() != null || target.column() != null || target.header() != null) {
                return "a metadata target has no row, column or header";
            }
            return metadataProblem(fields, target.group(), target.key(), from, to);
        }
        if (tab == null || !ROW_PREFIX.containsKey(tab)) {
            return "unknown tab '" + tab + "'";
        }
        if (target.group() != null || target.key() != null) {
            return "a " + tab + " target has no metadata group or key";
        }
        if (target.rowIndex() != null) {
            int row = rowLine(fields, ROW_PREFIX.get(tab), target.rowIndex());
            if (row < 0) {
                return "the " + tab + " tab has no row " + target.rowIndex();
            }
            if (row < from || row > to) {
                return "row " + target.rowIndex() + " of the " + tab + " tab is line " + row
                    + ", outside lines " + from + "-" + to;
            }
        }
        if (Boolean.TRUE.equals(target.header()) && target.column() == null) {
            return "a header target needs a column";
        }
        if (target.column() != null) {
            int header = headerLine(fields, HEADER_PREFIX.get(tab));
            if (header < 0 || !hasField(fields.get(header - 1), target.column())) {
                return "the " + tab + " header has no column '" + target.column() + "'";
            }
            if (Boolean.TRUE.equals(target.header()) && (header < from || header > to)) {
                return "the " + tab + " header is line " + header + ", outside lines " + from + "-" + to;
            }
        }
        return null;
    }

    @Nullable
    private static String metadataProblem(List<String[]> fields, @Nullable String group, @Nullable String key,
        int from, int to) {
        if (key != null) {
            if (group != null && !group.equals(groupOf(key))) {
                return "the key '" + key + "' is not in the group '" + group + "'";
            }
            if (!metadataKeyIn(fields, k -> k.equals(key), 1, fields.size())) {
                return "the file has no metadata key '" + key + "'";
            }
            if (!metadataKeyIn(fields, k -> k.equals(key), from, to)) {
                return "the metadata key '" + key + "' is outside lines " + from + "-" + to;
            }
            return null;
        }
        if (group != null && !metadataKeyIn(fields, k -> groupOf(k).equals(group), from, to)) {
            return "no metadata key of the group '" + group + "' on lines " + from + "-" + to;
        }
        return null;
    }

    /** The group of a metadata key as the viewer groups them: the part before the first {@code [}, else general. */
    static String groupOf(String key) {
        int bracket = key.indexOf('[');
        return bracket > 0 ? key.substring(0, bracket) : "general";
    }

    private static boolean metadataKeyIn(List<String[]> fields, Predicate<String> matches,
        int from, int to) {
        for (int line = from; line <= to; line++) {
            String[] f = fields.get(line - 1);
            if (f.length > 1 && "MTD".equals(f[0]) && matches.test(f[1])) {
                return true;
            }
        }
        return false;
    }

    /** The 1-based line of the {@code rowIndex}-th line with the prefix, or -1. */
    private static int rowLine(List<String[]> fields, String prefix, int rowIndex) {
        if (rowIndex < 0) {
            return -1;
        }
        int seen = 0;
        for (int i = 0; i < fields.size(); i++) {
            if (prefix.equals(fields.get(i)[0]) && seen++ == rowIndex) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The 1-based line of the first line with the header prefix, or -1. */
    private static int headerLine(List<String[]> fields, String prefix) {
        for (int i = 0; i < fields.size(); i++) {
            if (prefix.equals(fields.get(i)[0])) {
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean hasField(String[] header, String column) {
        for (int i = 1; i < header.length; i++) {
            if (header[i].equals(column)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
