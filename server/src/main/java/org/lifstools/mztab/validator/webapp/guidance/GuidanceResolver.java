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
package org.lifstools.mztab.validator.webapp.guidance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** Turns the messages of a validation into guidance, one panel per kind of problem. */
@Component
public class GuidanceResolver {

    /** The longest quoted line, in characters. */
    public static final int MAX_LINE_CHARS = 2000;

    /** The most lines quoted for one guidance. */
    public static final int MAX_QUOTED_LINES = 50;

    private final GuidanceCatalog catalog;

    public GuidanceResolver(GuidanceCatalog catalog) {
        this.catalog = catalog;
    }

    private static final class Group {
        final Optional<GuidanceEntry> entry;
        final String typeCode;
        final ValidationMessage first;
        final List<Integer> indexes = new ArrayList<>();
        final TreeSet<Long> lineNumbers = new TreeSet<>();

        Group(Optional<GuidanceEntry> entry, String typeCode, ValidationMessage first) {
            this.entry = entry;
            this.typeCode = typeCode;
            this.first = first;
        }
    }

    /**
     * @param messages the messages of one validation
     * @param line the mzTab-M line of the file, or null
     * @param softwareNames the names in the file's {@code MTD software[n]} values
     * @param lineTexts reads the text of the given 1-based line numbers; called once
     * @return one guidance per (entry or fallback, type code, rule id), in order of first appearance
     */
    public List<Guidance> resolve(List<ValidationMessage> messages, @Nullable String line,
        List<String> softwareNames, Function<Set<Long>, Map<Long, String>> lineTexts) {
        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            ValidationMessage m = messages.get(i);
            String typeCode = GuidanceCatalog.typeCodeOf(m);
            Optional<GuidanceEntry> entry = catalog.match(m, line);
            // Without a type code and without an entry, the text is all that tells messages apart.
            Object discriminator = entry.isEmpty() && typeCode.isEmpty() ? m.getMessage() : null;
            List<Object> key = java.util.Arrays.asList(entry.map(GuidanceEntry::id).orElse(null), typeCode,
                m.getRuleId(), discriminator);
            Group g = groups.computeIfAbsent(key, k -> new Group(entry, typeCode, m));
            g.indexes.add(i);
            Long n = m.getLineNumber();
            if (n != null && n > 0) {
                g.lineNumbers.add(n);
            }
        }
        Set<Long> wanted = new HashSet<>();
        Map<Group, List<Long>> chosen = new LinkedHashMap<>();
        for (Group g : groups.values()) {
            List<Long> first = g.lineNumbers.stream().limit(MAX_QUOTED_LINES).toList();
            chosen.put(g, first);
            wanted.addAll(first);
        }
        Map<Long, String> texts = wanted.isEmpty() ? Map.of() : lineTexts.apply(wanted);
        List<Guidance> out = new ArrayList<>();
        for (Map.Entry<Group, List<Long>> e : chosen.entrySet()) {
            out.add(toGuidance(e.getKey(), e.getValue(), texts, softwareNames));
        }
        return out;
    }

    private Guidance toGuidance(Group g, List<Long> lineNumbers, Map<Long, String> texts,
        List<String> softwareNames) {
        List<QuotedLine> quoted = new ArrayList<>();
        for (Long n : lineNumbers) {
            String text = texts.get(n);
            if (text != null) {
                boolean cut = text.length() > MAX_LINE_CHARS;
                quoted.add(new QuotedLine(n, cut ? text.substring(0, MAX_LINE_CHARS) : text, cut));
            }
        }
        ValidationMessage f = g.first;
        if (g.entry.isPresent()) {
            GuidanceEntry e = g.entry.get();
            String[] variant = toolVariant(e, softwareNames);
            return new Guidance(e.id(), g.typeCode, e.title(), e.explanation(), e.fixHere(), e.fixInTool(),
                variant == null ? null : variant[0], variant == null ? null : variant[1], e.quickFix(), e.stopsParsing(), f.getCause(), f.getSuggestion(),
                List.copyOf(g.indexes), quoted);
        }
        String title = Objects.requireNonNullElse(f.getMessage(), g.typeCode);
        return new Guidance(null, g.typeCode, title, Objects.requireNonNullElse(f.getCause(), ""),
            Objects.requireNonNullElse(f.getSuggestion(), ""), "", null, null, null, false, f.getCause(),
            f.getSuggestion(), List.copyOf(g.indexes), quoted);
    }

    /** The matching variant's fix and the file's software name that matched it, or null. */
    @Nullable
    private static String[] toolVariant(GuidanceEntry e, List<String> softwareNames) {
        for (ToolVariant t : e.tools()) {
            Pattern p = Pattern.compile(t.software(), Pattern.CASE_INSENSITIVE);
            for (String s : softwareNames) {
                if (p.matcher(s).find()) {
                    return new String[] {t.fixInTool(), s};
                }
            }
        }
        return null;
    }
}
