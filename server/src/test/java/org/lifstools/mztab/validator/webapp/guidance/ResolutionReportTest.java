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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.session.ProfileRef;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;

class ResolutionReportTest {

    private static ValidationRecord record() {
        return ValidationRecord.queued(UUID.randomUUID(), "my.mzTab", ValidationLevel.INFO, 100,
            new ProfileRef(ProfileRef.Kind.NONE, null, null), Instant.now(), Instant.now());
    }

    @Test
    void reportListsProblemsWithToolFixesAndLines() {
        Guidance g = new Guidance("empty-parameter", "Error-1012", "A parameter is empty", "Why.", "Do this here.",
            "Do this in the tool.", "Tool specific fix.", "MyTool 1.2", null, false, null, null, List.of(0, 1, 2),
            List.of(new QuotedLine(61, "MTD\ta\t[, , , ]", false), new QuotedLine(62, "MTD\tb", false),
                new QuotedLine(70, "MTD\tc", false)));
        String md = ResolutionReport.markdown(record(), List.of(g));
        assertTrue(md.contains("# Validation problems in my.mzTab"), md);
        assertTrue(md.contains("## A parameter is empty (3)"), md);
        assertTrue(md.contains("**Fix in the tool that wrote the file (MyTool 1.2):** Tool specific fix."), md);
        assertTrue(md.contains("Tool specific fix."), md);
        assertTrue(md.contains("Lines: 61, 62, 70"), md);
        assertTrue(md.contains("```\nMTD\ta\t[, , , ]"), md);
    }

    @Test
    void fallsBackToTheGeneralToolFix() {
        Guidance g = new Guidance("x", "Error-1", "T", "E", "H", "General tool fix.", null, null, null, false, null, null,
            List.of(0), List.of());
        assertTrue(ResolutionReport.markdown(record(), List.of(g)).contains("General tool fix."));
    }

    @Test
    void reportOfACleanFileSaysSo() {
        assertTrue(ResolutionReport.markdown(record(), List.of()).contains("No problems were found."));
    }

    @Test
    void aQuotedFenceCannotCloseTheBlock() {
        Guidance g = new Guidance("x", "Error-1", "T", "E", "H", "F", null, null, null, false, null, null, List.of(0),
            List.of(new QuotedLine(3, "a ``` b", false)));
        String md = ResolutionReport.markdown(record(), List.of(g));
        assertTrue(md.indexOf("```", md.indexOf("```") + 3) == md.lastIndexOf("```"), md);
    }

    @Test
    void aFileNameWithNewlineStaysOneHeading() {
        ValidationRecord r = ValidationRecord.queued(UUID.randomUUID(), "a\n## injected.mzTab", ValidationLevel.INFO,
            100, new ProfileRef(ProfileRef.Kind.NONE, null, null), Instant.now(), Instant.now());
        String md = ResolutionReport.markdown(r, List.of());
        assertTrue(md.startsWith("# Validation problems in a ## injected.mzTab\n"), md);
    }
}
