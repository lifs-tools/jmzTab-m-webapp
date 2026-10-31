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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.springframework.core.io.DefaultResourceLoader;

class GuidanceResolverTest {

    private final GuidanceResolver resolver = new GuidanceResolver(
        new GuidanceCatalog(new ObjectMapper(), new DefaultResourceLoader()));

    private static ValidationMessage msg(String code, String text, long line) {
        ValidationMessage m = new ValidationMessage();
        m.setCode(code);
        m.setMessage(text);
        m.setLineNumber(line);
        return m;
    }

    private static ValidationMessage param(long line) {
        return msg("[Error-1012] line " + line + ": \"MTD instrument[1]-detector\" value \"[, , , ]\" is not a valid parameter.",
            "\"MTD instrument[1]-detector\" value \"[, , , ]\" is not a valid parameter.", line);
    }

    @Test
    void groupsRepeatsAndQuotesTheirLines() {
        List<Guidance> g = resolver.resolve(List.of(param(61), param(62), param(70)), "2.0", List.of(),
            nums -> {
                Map<Long, String> m = new HashMap<>();
                nums.forEach(n -> m.put(n, "MTD\tline " + n));
                return m;
            });
        assertEquals(1, g.size());
        assertEquals(List.of(0, 1, 2), g.get(0).messages());
        assertEquals("empty-parameter", g.get(0).entryId());
        assertEquals("Error-1012", g.get(0).typeCode());
        assertEquals(List.of(61L, 62L, 70L), g.get(0).lines().stream().map(QuotedLine::lineNumber).toList());
        assertEquals("MTD\tline 61", g.get(0).lines().get(0).text());
    }

    @Test
    void picksTheToolVariant() {
        List<Guidance> lc = resolver.resolve(List.of(param(1)), "2.0", List.of("LipidCompass curation script"), n -> Map.of());
        assertNotNull(lc.get(0).toolFixInTool());
        assertEquals("LipidCompass curation script", lc.get(0).toolName());
        List<Guidance> mh = resolver.resolve(List.of(param(1)), "2.0", List.of("MassHunter"), n -> Map.of());
        assertNull(mh.get(0).toolFixInTool());
        assertNull(mh.get(0).toolName());
    }

    @Test
    void capsAHugeToolName() {
        String huge = "LipidCompass curation script" + "x".repeat(10_000);
        String name = resolver.resolve(List.of(param(1)), "2.0", List.of(huge), n -> Map.of()).get(0).toolName();
        assertEquals(201, name.length());
        assertTrue(name.endsWith("…"));
        // a surrogate pair straddling the cut is not split
        String pair = "LipidCompass curation script" + "x".repeat(171) + "\uD83D\uDE00" + "y".repeat(50);
        String cut = resolver.resolve(List.of(param(1)), "2.0", List.of(pair), n -> Map.of()).get(0).toolName();
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 2)));
    }

    @Test
    void fallsBackToJmzTabTexts() {
        ValidationMessage m = msg("[Error-9999] line 3: strange", "Something strange happened", 3);
        m.setCause("Because.");
        m.setSuggestion("Do this.");
        List<Guidance> g = resolver.resolve(List.of(m), "2.0", List.of(), n -> Map.of());
        assertEquals(1, g.size());
        assertNull(g.get(0).entryId());
        assertEquals("Error-9999", g.get(0).typeCode());
        assertEquals("Something strange happened", g.get(0).title());
        assertEquals("Because.", g.get(0).cause());
        assertEquals("Do this.", g.get(0).suggestion());
    }

    @Test
    void messagesWithoutACodeStaySeparateByText() {
        List<Guidance> g = resolver.resolve(List.of(msg("", "one", -1), msg("", "two", -1), msg("", "one", -1)),
            null, List.of(), n -> Map.of());
        assertEquals(2, g.size());
        assertEquals(List.of(0, 2), g.get(0).messages());
        assertEquals("", g.get(0).typeCode());
        assertTrue(g.get(0).lines().isEmpty());
    }

    @Test
    void truncatesLongLines() {
        List<Guidance> g = resolver.resolve(List.of(param(5)), "2.0", List.of(),
            n -> Map.of(5L, "x".repeat(5000)));
        QuotedLine q = g.get(0).lines().get(0);
        assertEquals(2000, q.text().length());
        assertTrue(q.truncated());
        List<Guidance> short1 = resolver.resolve(List.of(param(5)), "2.0", List.of(), n -> Map.of(5L, "abc"));
        assertFalse(short1.get(0).lines().get(0).truncated());
    }

    @Test
    void quotesAtMost50LinesPerGuidance() {
        List<ValidationMessage> all = new ArrayList<>();
        for (int i = 0; i < 270; i++) {
            all.add(param(61 + i));
        }
        List<Long> asked = new ArrayList<>();
        List<Guidance> g = resolver.resolve(all, "2.0", List.of(), nums -> {
            asked.addAll(nums);
            Map<Long, String> m = new HashMap<>();
            nums.forEach(n -> m.put(n, "t"));
            return m;
        });
        assertEquals(270, g.get(0).messages().size());
        assertEquals(50, g.get(0).lines().size());
        assertEquals(50, asked.size());
    }

    @Test
    void picksTheLipidDataAnalyzerVariantOnUndefinedReferences() {
        ValidationMessage m = msg("[Error-2008] line 9: \"sample[1]\" MUST be defined in the metadata section.",
            "\"sample[1]\" MUST be defined in the metadata section.", 9);
        List<Guidance> g = resolver.resolve(List.of(m), "2.0", List.of("LipidDataAnalyzer"), n -> Map.of());
        assertEquals("undefined-in-metadata", g.get(0).entryId());
        assertNotNull(g.get(0).toolFixInTool());
        assertTrue(g.get(0).toolFixInTool().contains("sample[1]"));
    }
}
