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
package org.lifstools.mztab.validator.webapp.service.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Sniffs the {@code MTD mzTab-version} line of an mzTab file. This is the only
 * hand-written mzTab reading in the application.
 */
public final class MzTabVersionDetector {

    private static final String VERSION_PREFIX = "MTD\tmzTab-version\t";

    private MzTabVersionDetector() {
    }

    /**
     * Returns the value of the first {@code MTD mzTab-version} line, reading
     * only the leading MTD/COM block of the file.
     *
     * @param file the file to inspect
     * @return the version, or empty if there is none
     * @throws IOException if the file cannot be read
     */
    public static Optional<String> detect(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(VERSION_PREFIX)) {
                    String value = line.substring(VERSION_PREFIX.length()).trim();
                    int tab = value.indexOf('\t');
                    return Optional.of(tab >= 0 ? value.substring(0, tab).trim() : value);
                }
                if (!line.startsWith("MTD\t") && !line.startsWith("COM\t") && !line.isBlank()) {
                    break;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @param version a version string as returned by {@link #detect(Path)}
     * @return true if it denotes an mzTab 1.x file
     */
    public static boolean isLegacy(String version) {
        return version != null && version.startsWith("1.");
    }
}
