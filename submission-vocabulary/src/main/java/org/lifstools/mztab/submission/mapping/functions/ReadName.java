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
package org.lifstools.mztab.submission.mapping.functions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lifstools.jgoslin.domain.LipidAdduct;
import org.lifstools.jgoslin.domain.LipidCategory;
import org.lifstools.mztab.submission.mapping.MappingRequestException;
import org.lifstools.mztab.submission.mapping.Render;
import org.lifstools.mztab.submission.mapping.Value;

/**
 * {@code read-name}: the readings of a lipid name, as {@code schema/mapping-language.md} defines it and the v3
 * converter's {@code read_name} does: a List of {@link GoslinParse} Objs, or Null when a reading is not read.
 */
final class ReadName {

    /** Python's whitespace, as in {@link StripNameNotes}. */
    private static final String WS = "[\\t-\\r\\x1c-\\x20\\x85\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]";
    /** Step 3: the separator after a sphingoid base written {@code _} ({@code SM(d18:1_14:0)}). */
    private static final Pattern SPHINGOID_UNDERSCORE = Pattern.compile(
            "\\A((?:(?!" + WS + ")[^(])+(?:" + WS + "|\\()(?:[dmt][0-9]+:[0-9]+|[0-9]+:[0-9]+;O[0-9]*))_");

    private ReadName() {
    }

    /**
     * The name's readings, or Null when a reading is not read. Null gives Null.
     *
     * @throws MappingRequestException when the name is not a text
     */
    static Value read(Value name) {
        if (name == Value.Null.INSTANCE) {
            return name;
        }
        if (!(name instanceof Value.Text text)) {
            throw new MappingRequestException("read-name needs a text, not " + name);
        }
        List<LipidAdduct> got = read(text.value());
        if (got == null) {
            return Value.Null.INSTANCE;
        }
        List<Value> readings = new ArrayList<>();
        for (LipidAdduct lipid : got) {
            readings.add(GoslinParse.reading(lipid));
        }
        return new Value.ListV(readings);
    }

    static List<LipidAdduct> read(String name) {
        if (name.isEmpty() || strip(name).isEmpty()) {
            return null;
        }
        LipidAdduct whole = known(name);
        if (whole != null) {
            return List.of(whole);
        }
        List<String> parts = parts(name, "|");
        if (parts.size() == 1) {
            List<String> semi = parts(parts.get(0), ";");
            if (semi.size() > 1) {
                List<LipidAdduct> read = readAll(semi);
                if (read != null) {
                    return distinct(read);
                }
            }
        }
        List<LipidAdduct> read = parts.isEmpty() ? null : readAll(parts);
        return read == null ? null : distinct(read);
    }

    private static List<LipidAdduct> readAll(List<String> parts) {
        List<LipidAdduct> out = new ArrayList<>();
        for (String part : parts) {
            LipidAdduct lipid = one(part);
            if (lipid == null) {
                return null;
            }
            out.add(lipid);
        }
        return out;
    }

    /** Step 3: as written, else without the notes at its end, else with a sphingoid base's {@code _} as {@code /}. */
    private static LipidAdduct one(String reading) {
        LipidAdduct got = known(reading);
        if (got != null) {
            return got;
        }
        String stripped = StripNameNotes.strip(reading);
        got = !stripped.isEmpty() && !stripped.equals(reading) ? known(stripped) : null;
        if (got != null) {
            return got;
        }
        Matcher m = SPHINGOID_UNDERSCORE.matcher(stripped);
        if (m.find()) {
            String rewritten = m.group(1) + "/" + stripped.substring(m.end());
            got = known(rewritten);
            if (got != null && got.getLipid().getHeadGroup().getLipidCategory() == LipidCategory.SP) {
                return got;
            }
        }
        return null;
    }

    /**
     * Goslin's reading when Goslin knows its lipid category: Goslin reads {@code 2} or {@code 0} as a lipid of an
     * undefined category, which would split {@code Cer 34:1;2} at {@code ;}.
     */
    private static LipidAdduct known(String name) {
        LipidAdduct got = GoslinParse.lipid(name);
        return got != null && got.getLipid().getHeadGroup().getLipidCategory() != LipidCategory.UNDEFINED ? got : null;
    }

    /** Step 5: each lipid once, at the finest level a reading names it; the order is kept. */
    private static List<LipidAdduct> distinct(List<LipidAdduct> readings) {
        List<String> names = readings.stream().map(LipidAdduct::getLipidString).toList();
        List<LipidAdduct> kept = new ArrayList<>();
        for (int i = 0; i < readings.size(); i++) {
            if (names.subList(0, i).contains(names.get(i))) {
                continue;
            }
            boolean coarser = false;
            for (int j = 0; j < readings.size() && !coarser; j++) {
                coarser = j != i && coarserOf(readings.get(j), readings.get(i));
            }
            if (!coarser) {
                kept.add(readings.get(i));
            }
        }
        return kept;
    }

    /** {@code fine} names {@code coarse} at a finer Goslin level: written at coarse's level, it is coarse's name. */
    private static boolean coarserOf(LipidAdduct fine, LipidAdduct coarse) {
        var fineLevel = fine.getLipid().getInfo().getLevel();
        var coarseLevel = coarse.getLipid().getInfo().getLevel();
        if (fineLevel.ordinal() <= coarseLevel.ordinal()) {
            return false;
        }
        try {
            return fine.getLipidString(coarseLevel).equals(coarse.getLipidString());
        } catch (RuntimeException e) { // jgoslin cannot write that level
            return false;
        }
    }

    /** Python {@code [p.strip() for p in s.split(sep) if p.strip()]}. */
    private static List<String> parts(String s, String sep) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(Pattern.quote(sep), -1)) {
            String t = strip(p);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && Render.isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && Render.isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }
}
