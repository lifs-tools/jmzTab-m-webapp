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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.lifstools.mztab.submission.mapping.Mapping;
import org.lifstools.mztab.submission.mapping.Notes;

/**
 * The {@code cv[n]} lines for the CVs the written metadata values use, as the v3 converter declares them
 * ({@code conv:281-288}): a parameter is a {@code |}-separated part that starts with {@code [}, and its CV is the
 * text before its first comma, without {@code [} and spaces at the ends. A CV is declared only when a value uses it
 * and the copy does not declare it yet; the mapping's {@code cvs} come first in their order, then any other labels
 * sorted, as {@code (label, "unknown", "null")} with a note.
 */
final class CvDeclarations {

    private CvDeclarations() {
    }

    /** The cv lines for {@code values}, numbered from the copy's next free cv index. */
    static List<MappingApplier.KeyValue> declare(List<String> values, Mapping mapping, CopyIndex copy, Notes notes) {
        Set<String> used = new LinkedHashSet<>();
        for (String v : values) {
            for (String part : v.split("\\|", -1)) {
                if (part.startsWith("[")) {
                    used.add(strip(part.split(",", -1)[0]));
                }
            }
        }
        used.remove("");
        List<String> labels = new ArrayList<>();
        mapping.cvs().keySet().stream().filter(used::contains).forEach(labels::add);
        new TreeSet<>(used).stream().filter(l -> !mapping.cvs().containsKey(l)).forEach(labels::add);
        List<MappingApplier.KeyValue> lines = new ArrayList<>();
        for (String label : labels) {
            if (copy.cvLabels().contains(label)) {
                continue;
            }
            Mapping.CvInfo info = mapping.cvs().get(label);
            if (info == null) {
                info = new Mapping.CvInfo(label, "unknown", "null");
                notes.add("cv '" + label + "' has no version or URI in the mapping " + mapping.id()
                        + "; add it to the mapping's cvs");
            }
            String cv = "cv[" + copy.reserve("cv") + "]";
            lines.add(new MappingApplier.KeyValue(cv + "-label", label));
            lines.add(new MappingApplier.KeyValue(cv + "-full_name", info.fullName()));
            lines.add(new MappingApplier.KeyValue(cv + "-version", info.version()));
            lines.add(new MappingApplier.KeyValue(cv + "-uri", info.uri()));
        }
        return lines;
    }

    /** Python {@code s.strip("[ ")}. */
    private static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '[' || s.charAt(start) == ' ')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '[' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(start, end);
    }
}
