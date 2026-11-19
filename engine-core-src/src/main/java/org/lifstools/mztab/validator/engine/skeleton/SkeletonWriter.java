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
package org.lifstools.mztab.validator.engine.skeleton;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.lifstools.mztab.submission.mapping.MappingRequestException;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.engine.api.SkeletonRequest;

/**
 * Writes the starting text of a new mzTab-M file: the version line, the {@code mzTab-ID} and, for 2.1, the
 * {@code mzTab-profile} (the content profile), in the order of the metadata of jmzTab-m. The fault of a request is the
 * caller's, so it is a {@link MappingRequestException} (answered with 400).
 */
public final class SkeletonWriter {

    /** The {@code mzTab-ID} entered on the form. */
    static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /** The content profiles of 2.1 (jmzTab-m's {@code MzTabProfile}). */
    static final List<String> CONTENT_PROFILES = List.of("M", "M+S", "M+F", "M+F+E", "M+S+F+E");

    private static final String TWO_ZERO = "2.0";

    private SkeletonWriter() {
    }

    /**
     * @param r the request
     * @param engineVersions the mzTab-M versions the engine serves, e.g. {@code 2.0.0-M}
     * @return the text, every line ended by a line break
     * @throws MappingRequestException if the version is not served, a content profile is given for 2.0 or is missing
     * or unknown for 2.1, or the id does not match the pattern
     */
    public static String write(SkeletonRequest r, Set<String> engineVersions) {
        String version = r.mzTabVersion() == null ? "" : r.mzTabVersion().strip();
        if (version.isEmpty() || !engineVersions.contains(version)) {
            throw new MappingRequestException("This engine does not serve mzTab-M version "
                + (version.isEmpty() ? "(none)" : shorten(version)) + "; it serves " + String.join(", ",
                engineVersions.stream().sorted().toList()) + ".");
        }
        if (r.mzTabId() == null || !ID.matcher(r.mzTabId()).matches()) {
            throw new MappingRequestException("The mzTab-ID must match " + ID.pattern());
        }
        boolean twoZero = MzTabVersions.lineOf(version).filter(TWO_ZERO::equals).isPresent();
        String profile = r.contentProfile();
        if (twoZero && profile != null) {
            throw new MappingRequestException("mzTab-M " + version + " has no content profile.");
        }
        if (!twoZero && (profile == null || !CONTENT_PROFILES.contains(profile))) {
            throw new MappingRequestException("mzTab-M " + version + " needs a content profile, one of "
                + String.join(", ", CONTENT_PROFILES) + ".");
        }
        StringBuilder text = new StringBuilder();
        text.append("MTD\tmzTab-version\t").append(version).append('\n');
        text.append("MTD\tmzTab-ID\t").append(r.mzTabId()).append('\n');
        if (profile != null) {
            text.append("MTD\tmzTab-profile\t").append(profile).append('\n');
        }
        return text.toString();
    }

    private static String shorten(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
