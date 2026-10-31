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
package org.lifstools.mztab.validator.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ErrorCausesTest {

    @Test
    void causeOfAKnownCode() {
        assertTrue(ErrorCauses.causeOf("[Error-1000] line 5: \"x\" MUST start with ...").isPresent());
    }

    @Test
    void causeOfMessagesWithoutTypeCode() {
        assertEquals(Optional.empty(), ErrorCauses.causeOf(""));
        assertEquals(Optional.empty(), ErrorCauses.causeOf("json_not_read"));
        assertEquals(Optional.empty(), ErrorCauses.causeOf("[Error-9999] line 1: unknown"));
    }

    @Test
    void causesHaveNoLeadingEqualsSignOrWhitespace() {
        new uk.ac.ebi.pride.jmztab2.utils.errors.MZTabErrorTypeMap().getTypeMap().keySet().forEach(code ->
            ErrorCauses.causeOf("[Error-" + code + "] x").ifPresent(c -> {
                assertFalse(c.startsWith("="), code + ": " + c);
                assertEquals(c.strip(), c);
            }));
    }
}
