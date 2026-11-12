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
package org.lifstools.mztab.validator.engine.api;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a declared {@code mzTab-version} to a version line such as {@code 2.0}
 * or {@code 2.1}.
 */
public final class MzTabVersions {

    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d+)(\\.|$)");

    private MzTabVersions() {
    }

    /**
     * @param declared the declared version, e.g. {@code 2.0.0-M}
     * @return {@code MAJOR.MINOR}, or empty if the version cannot be parsed
     */
    public static Optional<String> lineOf(String declared) {
        if (declared == null) {
            return Optional.empty();
        }
        Matcher m = LINE.matcher(declared);
        return m.find() ? Optional.of(m.group(1) + "." + m.group(2)) : Optional.empty();
    }

    /**
     * @param declared the declared version
     * @return true if it denotes an mzTab 1.x file
     */
    public static boolean isLegacy(String declared) {
        return declared != null && declared.stripLeading().startsWith("1.");
    }
}
