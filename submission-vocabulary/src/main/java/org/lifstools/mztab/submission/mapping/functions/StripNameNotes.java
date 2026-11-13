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

import java.util.List;
import java.util.regex.Pattern;
import org.lifstools.mztab.submission.mapping.MappingRequestException;
import org.lifstools.mztab.submission.mapping.Render;
import org.lifstools.mztab.submission.mapping.Value;

/**
 * {@code strip-name-notes}: a lipid name without the custom suffixes and comments at its end that no Goslin grammar
 * reads, as {@code schema/mapping-language.md} defines it and the v3 converter's {@code strip_name_notes} does.
 */
final class StripNameNotes {

    /** Python's whitespace ({@code str.isspace()}), as a regex character class. */
    private static final String WS = "[\\t-\\r\\x1c-\\x20\\x85\\xa0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]";
    private static final Pattern COMMENT = Pattern.compile(WS + "*//.*\\z", Pattern.DOTALL);
    private static final Pattern SQUARE_NOTE = Pattern.compile(WS + "*\\[[^\\[\\]]*\\]\\z");
    private static final Pattern UNCLOSED = Pattern.compile(WS + "+\\([^()]*\\z");
    private static final Pattern ISOMER = Pattern.compile("_[A-Z]\\z");
    private static final Pattern ADDUCT = Pattern.compile("_M[+-][A-Za-z0-9+-]*\\]?[+-]?\\z");
    private static final Pattern SQUARE_ADDUCT = Pattern.compile(WS + "*\\[M[+-][^\\]]*\\][+-]?\\z");
    private static final List<Pattern> AFTER_ROUND = List.of(SQUARE_NOTE, UNCLOSED, ISOMER, ADDUCT, SQUARE_ADDUCT);

    private StripNameNotes() {
    }

    /**
     * The name without its trailing notes; Null gives Null.
     *
     * @throws MappingRequestException when the name is not a text
     */
    static Value strip(Value name) {
        if (name == Value.Null.INSTANCE) {
            return name;
        }
        if (!(name instanceof Value.Text text)) {
            throw new MappingRequestException("strip-name-notes needs a text, not " + name);
        }
        return new Value.Text(strip(text.value()));
    }

    static String strip(String name) {
        String prev = null;
        String s = name;
        while (!s.equals(prev)) {
            prev = s;
            s = rstrip(COMMENT.matcher(s).replaceFirst(""));
            s = rstrip(roundComment(s));
            for (Pattern p : AFTER_ROUND) {
                s = rstrip(p.matcher(s).replaceFirst(""));
            }
        }
        return s;
    }

    /** Step 2: a final {@code (...)} whose brackets nest, removed when whitespace comes before its opening bracket. */
    private static String roundComment(String s) {
        if (!s.endsWith(")")) {
            return s;
        }
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            depth += c == ')' ? 1 : c == '(' ? -1 : 0;
            if (depth == 0) {
                return i > 0 && Render.isSpace(s.charAt(i - 1)) ? s.substring(0, i) : s;
            }
        }
        return s;
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && Render.isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
