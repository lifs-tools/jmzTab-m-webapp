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
package org.lifstools.mztab.submission.mapping;

import org.lifstools.mztab.submission.Vocabulary.Param;

/**
 * Cell text as the v3 converter writes it ({@code tools/convert_submission_template.py} at lipidcompass-submissions
 * bb8dcbf, lines 112-126). Whitespace is Python's: the characters for which {@code str.isspace()} is true.
 */
public final class Render {

    private Render() {
    }

    /** Python {@code " ".join(s.split())}: whitespace runs become one space, and the ends are trimmed. */
    public static String text(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean gap = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpace(c)) {
                gap = out.length() > 0;
            } else {
                if (gap) {
                    out.append(' ');
                    gap = false;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /** {@link #text}, double quotes made single, and the whole in double quotes when it contains a comma. */
    public static String quoted(String s) {
        String v = text(s).replace('"', '\'');
        return v.indexOf(',') >= 0 ? "\"" + v + "\"" : v;
    }

    /**
     * A parameter as an mzTab-M cell, {@code [cv, accession, name, value]}: the cv only with an accession, the name
     * and the value {@link #quoted}. Null when there is neither an accession nor a name, as jmzTab-m rejects such a
     * parameter (Error-1012).
     */
    public static String param(Param p) {
        String accession = orEmpty(p.accession());
        String name = orEmpty(p.name());
        String value = orEmpty(p.value());
        if (accession.isEmpty() && text(name).isEmpty()) {
            return null;
        }
        return "[" + (accession.isEmpty() ? "" : orEmpty(p.cv())) + ", " + accession + ", " + quoted(name) + ", "
                + (value.isEmpty() ? "" : quoted(value)) + "]";
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Python {@code str.isspace()} for one character. */
    static boolean isSpace(char c) {
        return (c >= '\t' && c <= '\r') || (c >= '\u001c' && c <= ' ') || c == '\u0085' || c == ' ' || c == ' '
                || (c >= ' ' && c <= ' ') || c == ' ' || c == ' ' || c == ' ' || c == ' '
                || c == '　';
    }

    /** Python {@code s.strip()}. */
    static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }
}
