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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOIs and e-mail addresses as the v3 converter writes them into mzTab-M: a port of lipidcompass-submissions bb8dcbf
 * {@code src/curation/identifiers.py}. Python's {@code \d} is any Unicode decimal digit and its {@code \s} is
 * {@link Render#isSpace}; the patterns below spell both out.
 */
public final class Identifiers {

    private static final String PY_SPACE = "\\t\\n\\x0B\\f\\r\\x1C-\\x20\\x85\\xA0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";

    /** {@code 10\.\d{4,}/\S+} */
    private static final Pattern DOI_RE = Pattern.compile("10\\.\\p{Nd}{4,}/[^" + PY_SPACE + "]+");
    /** {@code ^(?:doi:|https?://(?:dx\.)?doi\.org/)}, ignoring case. */
    private static final Pattern DOI_PREFIX = Pattern.compile("^(?:doi:|https?://(?:dx\\.)?doi\\.org/)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String DOI_TRAIL = ".,;:)]}'\"";
    /** jmzTab-m's {@code MZTabConstants.REGEX_EMAIL} (Error-1017), matched in full. */
    private static final Pattern EMAIL = Pattern.compile(
            "[_A-Za-z0-9-]+(\\.[_A-Za-z0-9\\-']+)*@[A-Za-z0-9]+(?:[-.][A-Za-z0-9]+)*(\\.[A-Za-z]{2,})");

    private Identifiers() {
    }

    /**
     * The DOI to write after {@code doi:} in a publication item ({@code mztab_doi}), or null when {@code raw} holds
     * none. A DOI with a colon of its own is null as well, since jmzTab-m splits an item at {@code :}.
     */
    public static String doi(String raw) {
        String doi = bareDoi(raw == null ? "" : raw);
        return DOI_RE.matcher(doi).matches() && doi.indexOf(':') < 0 ? doi : null;
    }

    /** The DOI itself, without a prefix, surrounding whitespace, trailing {@code /} or trailing punctuation ({@code bare_doi}). */
    static String bareDoi(String raw) {
        String s = Render.strip(raw);
        Matcher m = DOI_RE.matcher(s);
        s = m.find() ? m.group() : Render.strip(DOI_PREFIX.matcher(s).replaceFirst(""));
        int end = s.length();
        while (end > 0 && (DOI_TRAIL.indexOf(s.charAt(end - 1)) >= 0 || s.charAt(end - 1) == '/')) {
            end--;
        }
        return s.substring(0, end);
    }

    /** True when jmzTab-m accepts {@code s} as a {@code contact[]-email} ({@code valid_email}). */
    public static boolean email(String s) {
        return s != null && !s.isEmpty() && EMAIL.matcher(Render.strip(s)).matches();
    }
}
