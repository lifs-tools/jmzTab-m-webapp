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
package org.lifstools.mztab.validator.webapp.guidance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.springframework.core.io.DefaultResourceLoader;

class GuidanceCatalogTest {

    private final GuidanceCatalog catalog = new GuidanceCatalog(new ObjectMapper(), new DefaultResourceLoader());

    static ValidationMessage message(String code, String text) {
        ValidationMessage m = new ValidationMessage();
        m.setCode(code);
        m.setMessage(text);
        return m;
    }

    private static Set<Integer> numeric(Set<String> typeCodes) {
        return typeCodes.stream().map(c -> Integer.parseInt(c.substring(c.lastIndexOf('-') + 1)))
            .collect(Collectors.toSet());
    }

    @Test
    void everyCatalogCodeExistsInAnEngine() {
        // CV-rule messages take the rule's level, so only the number is compared.
        Set<Integer> known = new HashSet<>(numeric(ErrorCodeList.codes("2.0")));
        known.addAll(numeric(ErrorCodeList.codes("2.1")));
        for (GuidanceEntry e : catalog.entries()) {
            for (String code : e.codes()) {
                assertTrue(known.contains(GuidanceCatalog.numericPart(code)), e.id() + ": " + code);
            }
        }
    }

    @Test
    void everyEntryHasBothFixes() {
        for (GuidanceEntry e : catalog.entries()) {
            assertFalse(e.fixHere().isBlank(), e.id());
            assertFalse(e.fixInTool().isBlank(), e.id());
            assertFalse(e.explanation().isBlank(), e.id());
            assertTrue(e.title().length() <= 60, e.id());
        }
    }

    @Test
    void coversEveryCodeSeenInRealFiles() {
        Set<String> covered = catalog.entries().stream().flatMap(e -> e.codes().stream())
            .map(c -> String.valueOf(GuidanceCatalog.numericPart(c))).collect(Collectors.toSet());
        for (String code : List.of("Error-1000", "Error-1004", "Error-1008", "Error-1009", "Error-1012",
            "Error-1013", "Error-1014", "Error-1017", "Error-2000", "Error-2002", "Error-2004", "Error-2008",
            "Error-3015", "Warn-3005", "Warn-3014", "Info-3010", "Info-2037", "Info-2038")) {
            assertTrue(covered.contains(code.substring(code.indexOf('-') + 1)), code);
        }
    }

    @Test
    void emptyParameterAndUnnamedUserParamDiffer() {
        ValidationMessage empty = message("[Error-1012] line 57: \"MTD instrument[1]-detector\" value \"[, , , ]\" is not a valid parameter.",
            "\"MTD instrument[1]-detector\" value \"[, , , ]\" is not a valid parameter.");
        ValidationMessage unnamed = message("[Error-1012] line 61: \"MTD instrument[1]-detector\" value \"[, , , 25]\" is not a valid parameter.",
            "\"MTD instrument[1]-detector\" value \"[, , , 25]\" is not a valid parameter.");
        String a = catalog.match(empty, "2.0").orElseThrow().id();
        String b = catalog.match(unnamed, "2.0").orElseThrow().id();
        assertNotEquals(a, b);
        assertEquals("empty-parameter", a);
    }

    @Test
    void matchesAnyLevelOfTheSameNumber() {
        // Error-3005 is emitted for a rule that requires the term, although the list records Warn.
        assertTrue(catalog.match(message("[Error-3005] x", "The cv term is not allowed"), "2.1").isPresent());
    }

    @Test
    void unknownCodeHasNoEntry() {
        assertTrue(catalog.match(message("[Error-9999] x", "x"), "2.0").isEmpty());
        assertTrue(catalog.match(message("", "x"), "2.0").isEmpty());
    }

    @Test
    void stopsParsingSet() {
        Set<Integer> stops = catalog.entries().stream().filter(GuidanceEntry::stopsParsing)
            .flatMap(e -> e.codes().stream()).map(GuidanceCatalog::numericPart).collect(Collectors.toSet());
        assertTrue(stops.containsAll(Set.of(1000, 1004, 1008, 1009, 2002, 2004)));
    }
}
