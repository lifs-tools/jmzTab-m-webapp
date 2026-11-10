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
    void causeAppearsOnceForCatalogAndFallbackGroups() {
        Guidance cat = new Guidance("x", "Error-1", "T", "Why.", "H", "F", null, null, null, false, "Validator cause.", null,
            List.of(0), List.of());
        String md = ResolutionReport.markdown(record(), List.of(cat));
        assertEquals(1, md.split("Validator cause\\.", -1).length - 1, md);
        assertTrue(md.contains("Cause: Validator cause."), md);
        Guidance fb = new Guidance(null, "Error-2", "T2", "Only text.", "", "", null, null, null, false, null, null,
            List.of(0), List.of());
        String md2 = ResolutionReport.markdown(record(), List.of(fb));
        assertEquals(1, md2.split("Only text\\.", -1).length - 1, md2);
        assertFalse(md2.contains("Cause:"), md2);
    }

    @Test
    void aFallbackGroupHasNoEmptyFixInToolLine() {
        org.lifstools.mztab.validator.engine.api.ValidationMessage m =
            new org.lifstools.mztab.validator.engine.api.ValidationMessage();
        m.setCode("[Error-9999] line 3: nothing the catalog knows");
        m.setMessage("nothing the catalog knows");
        m.setLineNumber(3L);
        GuidanceResolver resolver = new GuidanceResolver(new GuidanceCatalog(
            new com.fasterxml.jackson.databind.ObjectMapper(), new org.springframework.core.io.DefaultResourceLoader()));
        List<Guidance> groups = resolver.resolve(List.of(m), null, List.of(), n -> java.util.Map.of());
        assertEquals(1, groups.size());
        assertEquals(null, groups.get(0).entryId());
        String md = ResolutionReport.markdown(record(), groups);
        assertFalse(md.contains("Fix in the tool"), md);
        assertTrue(md.contains("nothing the catalog knows"), md);
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

    private static final Guidance WITH_LINES = new Guidance("empty-parameter", "Error-1012", "A parameter is empty", "Why.",
        "Do this here.", "Do this in the tool.", null, null, null, false, null, null, List.of(0),
        List.of(new QuotedLine(23, "MTD\ta\t[, , , ]", false)));

    private static ResolutionReport.CopyCheck check(long checked, long current, boolean running, boolean semantic,
        ProfileRef profile) {
        return new ResolutionReport.CopyCheck("my.mzTab", "2.0.0-M", checked, current,
            Instant.parse("2026-11-09T10:15:30Z"), semantic, profile, running, null, null, null);
    }

    @Test
    void reportOfACopyNamesTheCopyItsRevisionAndTheCheck() {
        String md = ResolutionReport.markdown(check(3, 3, false, false, null), List.of(WITH_LINES));
        assertTrue(md.startsWith("# Validation problems in my.mzTab (corrected copy)\n"), md);
        assertTrue(md.contains("Revision 3 of the corrected copy"), md);
        assertTrue(md.contains("mzTab-M version 2.0.0-M"), md);
        assertTrue(md.contains("2026-11-09T10:15:30Z"), md);
        assertTrue(md.contains("structure only"), md);
        assertFalse(md.contains("the copy is at revision"), md);
        assertTrue(md.contains("## A parameter is empty (1)"), md);
        assertTrue(md.contains("Lines: 23"), md);
    }

    @Test
    void reportOfACopySaysWhenTheCheckIsNotCurrentAndWhenOneIsRunning() {
        String md = ResolutionReport.markdown(check(2, 5, true, false, null), List.of());
        assertTrue(md.contains("check of revision 2; the copy is at revision 5"), md);
        assertTrue(md.contains("A new check is running."), md);
        assertTrue(md.contains("No problems were found."), md);
        assertFalse(ResolutionReport.markdown(check(2, 5, false, false, null), List.of()).contains("is running"));
    }

    @Test
    void reportOfACopyNamesTheCvRulesProfile() {
        assertTrue(ResolutionReport.markdown(check(1, 1, false, true, ProfileRef.catalog("lipidomics", "1.0")),
            List.of()).contains("CV rules of the profile lipidomics 1.0"));
        assertTrue(ResolutionReport.markdown(check(1, 1, false, true, ProfileRef.custom()), List.of())
            .contains("CV rules of the uploaded mapping file"));
        assertTrue(ResolutionReport.markdown(check(1, 1, false, true, ProfileRef.none()), List.of())
            .contains("CV rules of the default profile of the file's version"));
    }

    @Test
    void reportOfAStructuralCheckSaysWhichCvCheckItsCvMessagesAreOf() {
        ResolutionReport.CopyCheck kept = new ResolutionReport.CopyCheck("my.mzTab", "2.0.0-M", 3, 3, null, false,
            ProfileRef.catalog("lipidomics", "1.0"), false, null, null, null, 2L);
        assertTrue(ResolutionReport.markdown(kept, List.of()).contains(
            "The CV rule messages are those of the check of revision 2; their line numbers are those of revision 2."));
        ResolutionReport.CopyCheck upload = new ResolutionReport.CopyCheck("my.mzTab", "2.0.0-M", 3, 3, null, false,
            ProfileRef.catalog("lipidomics", "1.0"), false, null, null, null, 0L);
        assertTrue(ResolutionReport.markdown(upload, List.of()).contains(
            "The CV rule messages are those of the validation of the upload (revision 0); their line numbers are "
            + "those of revision 0."));
        // a structural check of the revision the CV rules were checked at has one set of line numbers
        ResolutionReport.CopyCheck same = new ResolutionReport.CopyCheck("my.mzTab", "2.0.0-M", 3, 3, null, false,
            ProfileRef.catalog("lipidomics", "1.0"), false, null, null, null, 3L);
        assertTrue(ResolutionReport.markdown(same, List.of()).contains(
            "The CV rule messages are those of the check of revision 3.\n"));
        // a CV check's own messages, and a copy whose CV rules were never checked, need no such line
        assertFalse(ResolutionReport.markdown(check(3, 3, false, true, ProfileRef.catalog("lipidomics", "1.0")),
            List.of()).contains("The CV rule messages are"));
        assertFalse(ResolutionReport.markdown(check(3, 3, false, false, null), List.of())
            .contains("The CV rule messages are"));
    }

    @Test
    void reportOfACopyKeepsFileNameAndVersionOnOneLine() {
        ResolutionReport.CopyCheck c = new ResolutionReport.CopyCheck("a\n## injected.mzTab", "2.0\n# x", 1, 1, null,
            false, null, false, null, null, null);
        String md = ResolutionReport.markdown(c, List.of());
        assertTrue(md.startsWith("# Validation problems in a ## injected.mzTab (corrected copy)\n"), md);
        assertFalse(md.contains("\n# x"), md);
    }

    @Test
    void reportSaysWhereTheEngineStoppedReading() {
        ResolutionReport.CopyCheck c = new ResolutionReport.CopyCheck("my.mzTab", "2.0.0-M", 1, 1, null, false, null,
            false, 12L, null, null);
        String md = ResolutionReport.markdown(c, List.of(WITH_LINES));
        assertTrue(md.contains("jmzTab-m stopped reading at line 12; problems further down are not reported yet."), md);
        assertFalse(ResolutionReport.markdown(check(1, 1, false, false, null), List.of(WITH_LINES))
            .contains("stopped reading"));
    }

    @Test
    void reportSaysWhenTheLatestCheckGaveNoResult() {
        ResolutionReport.CopyCheck c = new ResolutionReport.CopyCheck("my.mzTab", "2.0.0-M", 1, 3, null, false, null,
            false, null, 3L, "The validator is down.\nTry later.");
        String md = ResolutionReport.markdown(c, List.of());
        assertTrue(md.contains("The latest check, of revision 3, gave no result: The validator is down. Try later."), md);
        assertFalse(ResolutionReport.markdown(check(1, 1, false, false, null), List.of())
            .contains("gave no result"));
    }
}
