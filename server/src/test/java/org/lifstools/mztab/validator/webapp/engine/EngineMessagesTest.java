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
package org.lifstools.mztab.validator.webapp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EngineMessagesTest {

    /** F4: the legacy message names the configured lines, joined as in the unsupported message. */
    @Test
    void legacyMessageNamesTheConfiguredLines() {
        assertEquals("This file declares mzTab-version 1.0. mzTab 1.0 is no longer supported; the validator checks "
            + "mzTab-M 2.0 files.", EngineMessages.legacy(Set.of("2.0")));
        assertEquals("This file declares mzTab-version 1.0. mzTab 1.0 is no longer supported; the validator checks "
            + "mzTab-M 2.0 and 2.1 files.", EngineMessages.legacy(new LinkedHashSet<>(List.of("2.0", "2.1"))));
    }

    @Test
    void rejectionOfALegacyFileIsTheLegacyMessage() {
        Set<String> lines = new LinkedHashSet<>(List.of("2.0", "2.1"));
        assertEquals(Optional.of(EngineMessages.legacy(lines)),
            EngineMessages.rejection(new EngineRegistry.RouteDecision.Legacy("1.0.0", lines), null));
    }
}
