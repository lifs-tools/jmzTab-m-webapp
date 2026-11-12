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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MzTabVersionsTest {

    @ParameterizedTest
    @CsvSource({"2.0.0-M,2.0", "'2.0.0-M ',2.0", "2.1.0-m,2.1", "2.1,2.1", "2.0.1-M,2.0", "2.2.0-M,2.2"})
    void lineOfAcceptsRealWorldSpellings(String declared, String line) {
        assertEquals(Optional.of(line), MzTabVersions.lineOf(declared));
    }

    @Test
    void lineOfRejectsGarbage() {
        assertEquals(Optional.empty(), MzTabVersions.lineOf("M-2"));
        assertEquals(Optional.empty(), MzTabVersions.lineOf(null));
    }

    @Test
    void legacyIsOneDotAnything() {
        assertTrue(MzTabVersions.isLegacy("1.0.0"));
        assertFalse(MzTabVersions.isLegacy("2.0.0-M"));
        assertFalse(MzTabVersions.isLegacy(null));
    }
}
