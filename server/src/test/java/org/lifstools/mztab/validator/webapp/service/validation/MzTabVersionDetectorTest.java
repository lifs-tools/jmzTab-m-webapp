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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MzTabVersionDetectorTest {

    @TempDir
    Path dir;

    @Test
    void detectsMzTabMVersion() throws IOException {
        Path f = dir.resolve("minimal.mztab");
        try (InputStream in = getClass().getResourceAsStream("/metabolomics/minimal-m-2.0.mztab")) {
            Files.copy(in, f, StandardCopyOption.REPLACE_EXISTING);
        }
        assertEquals(Optional.of("2.0.0-M"), MzTabVersionDetector.detect(f));
    }

    @Test
    void detectsLegacyVersion() throws IOException {
        Path f = Files.writeString(dir.resolve("legacy.mztab"), "MTD\tmzTab-version\t1.0.0\n");
        assertEquals(Optional.of("1.0.0"), MzTabVersionDetector.detect(f));
        assertTrue(MzTabVersionDetector.isLegacy("1.0.0"));
        assertFalse(MzTabVersionDetector.isLegacy("2.0.0-M"));
    }

    @Test
    void missingVersionLineIsEmpty() throws IOException {
        Path f = Files.writeString(dir.resolve("none.mztab"), "COM\tnothing\n");
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(f));
    }

    @Test
    void readsOnlyUntilMetadataEnds() throws IOException {
        Path f = Files.writeString(dir.resolve("late.mztab"),
            "COM\tx\nMTD\tmzTab-ID\ta\nSMH\tx\nMTD\tmzTab-version\t2.0.0-M\n");
        assertEquals(Optional.empty(), MzTabVersionDetector.detect(f));
    }
}
