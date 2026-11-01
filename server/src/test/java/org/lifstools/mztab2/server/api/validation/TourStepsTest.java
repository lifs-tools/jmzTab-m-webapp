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
package org.lifstools.mztab2.server.api.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TourStepsTest {

    /** Line numbers are 1-based: line 1 is the COM line. */
    static final List<String> FILE = List.of(
        "COM\tA test file",                                   // 1
        "MTD\tmzTab-version\t2.0.0-M",                       // 2
        "MTD\tmzTab-ID\ttest-1",                              // 3
        "MTD\tinstrument[1]-name\t[MS, MS:1001911, Q Exactive, ]", // 4
        "MTD\tinstrument[1]-source\t[MS, MS:1000073, ESI, ]", // 5
        "",                                                   // 6
        "SMH\tSML_ID\tSMF_ID_REFS\tchemical_name",            // 7
        "SML\t1\t1 | 2\tCer 18:1;O2/24:0",                    // 8
        "SML\t2\t3\tPC 34:1",                                 // 9
        "",                                                   // 10
        "SFH\tSMF_ID\tSME_ID_REFS\tcharge",                   // 11
        "SMF\t1\t1\t1",                                       // 12
        "",                                                   // 13
        "SEH\tSME_ID\tchemical_name",                         // 14
        "SME\t1\tCer 18:1;O2/24:0");                          // 15

    static TourStepDto step(String id, String chapter, TourTargetDto target, int from, int to) {
        return new TourStepDto(id, chapter, "A title", "Some text.", null, target, List.of(from, to));
    }

    static TourDto tour(TourStepDto... steps) {
        return new TourDto("test.mztab", "A tour", null, List.of(steps));
    }

    static TourTargetDto metadata(String group, String key) {
        return new TourTargetDto("metadata", group, key, null, null, null);
    }

    static TourTargetDto table(String tab, Integer rowIndex, String column, Boolean header) {
        return new TourTargetDto(tab, null, null, rowIndex, column, header);
    }

    static void assertOneProblemNaming(String stepId, List<String> problems) {
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains(stepId), problems.toString());
    }

    @Test
    void acceptsAValidTour() {
        assertEquals(List.of(), TourSteps.problems(FILE, tour(
            step("sections", "overview", null, 1, 15),
            step("id", "metadata", metadata("general", "mzTab-ID"), 2, 3),
            step("general", "metadata", metadata("general", null), 2, 3),
            step("row", "summary", table("summary", 1, "chemical_name", null), 7, 9),
            step("column", "features", table("features", null, "SME_ID_REFS", true), 11, 12),
            step("evidence", "evidence", table("evidence", 0, null, null), 14, 15))));
    }

    @Test
    void rejectsDuplicateIds() {
        assertOneProblemNaming("twice", TourSteps.problems(FILE, tour(
            step("twice", "overview", null, 1, 2), step("twice", "overview", null, 1, 2))));
    }

    @Test
    void rejectsBlankIdsTitlesAndTexts() {
        List<String> problems = TourSteps.problems(FILE, tour(
            new TourStepDto(" ", "overview", "A title", "Some text.", null, null, List.of(1, 2)),
            new TourStepDto("blank-title", "overview", "", "Some text.", null, null, List.of(1, 2)),
            new TourStepDto("blank-text", "overview", "A title", null, null, null, List.of(1, 2))));
        assertEquals(3, problems.size(), problems.toString());
    }

    @Test
    void rejectsUnknownChapter() {
        assertOneProblemNaming("chapter", TourSteps.problems(FILE, tour(step("chapter", "appendix", null, 1, 2))));
    }

    @Test
    void rejectsLinesOutOfRange() {
        assertOneProblemNaming("zero", TourSteps.problems(FILE, tour(step("zero", "overview", null, 0, 2))));
        assertOneProblemNaming("past-end", TourSteps.problems(FILE, tour(step("past-end", "overview", null, 14, 16))));
        assertOneProblemNaming("reversed", TourSteps.problems(FILE, tour(step("reversed", "overview", null, 3, 2))));
        assertOneProblemNaming("one-value", TourSteps.problems(FILE, tour(
            new TourStepDto("one-value", "overview", "A title", "Some text.", null, null, List.of(1)))));
    }

    @Test
    void rejectsKeyOutsideLines() {
        assertOneProblemNaming("outside", TourSteps.problems(FILE, tour(
            step("outside", "metadata", metadata(null, "mzTab-ID"), 4, 5))));
    }

    @Test
    void rejectsUnknownKey() {
        assertOneProblemNaming("unknown", TourSteps.problems(FILE, tour(
            step("unknown", "metadata", metadata(null, "mzTab-nonsense"), 1, 15))));
    }

    @Test
    void rejectsGroupNotOfKey() {
        assertOneProblemNaming("mismatch", TourSteps.problems(FILE, tour(
            step("mismatch", "metadata", metadata("instrument", "mzTab-ID"), 2, 3))));
    }

    @Test
    void acceptsGroupWithIndexedKeys() {
        assertEquals(List.of(), TourSteps.problems(FILE, tour(
            step("instrument", "metadata", metadata("instrument", null), 4, 5),
            step("one", "metadata", metadata("instrument", "instrument[1]-name"), 4, 4))));
        // a group matches whole keys or indexed keys only, not every key it is a prefix of
        assertOneProblemNaming("prefix", TourSteps.problems(FILE, tour(
            step("prefix", "metadata", metadata("instr", null), 4, 5))));
        // and only inside the step's lines
        assertOneProblemNaming("elsewhere", TourSteps.problems(FILE, tour(
            step("elsewhere", "metadata", metadata("instrument", null), 2, 3))));
    }

    @Test
    void rejectsRowIndexBeyondRows() {
        assertOneProblemNaming("beyond", TourSteps.problems(FILE, tour(
            step("beyond", "summary", table("summary", 2, null, null), 7, 9))));
        assertOneProblemNaming("negative", TourSteps.problems(FILE, tour(
            step("negative", "summary", table("summary", -1, null, null), 7, 9))));
        // the row exists, but is not shown
        assertOneProblemNaming("outside", TourSteps.problems(FILE, tour(
            step("outside", "summary", table("summary", 1, null, null), 7, 8))));
    }

    @Test
    void rejectsColumnMissingFromHeader() {
        assertOneProblemNaming("missing", TourSteps.problems(FILE, tour(
            step("missing", "features", table("features", null, "chemical_name", true), 11, 12))));
        // the header line must be shown when the header is the target
        assertOneProblemNaming("header-outside", TourSteps.problems(FILE, tour(
            step("header-outside", "features", table("features", null, "charge", true), 12, 12))));
    }

    @Test
    void rejectsUnknownTabAndFieldsOfTheOtherKind() {
        assertOneProblemNaming("tab", TourSteps.problems(FILE, tour(
            step("tab", "overview", table("messages", null, null, null), 1, 2))));
        assertOneProblemNaming("row-on-metadata", TourSteps.problems(FILE, tour(
            step("row-on-metadata", "metadata", table("metadata", 0, null, null), 2, 3))));
        assertOneProblemNaming("key-on-table", TourSteps.problems(FILE, tour(
            step("key-on-table", "summary", new TourTargetDto("summary", null, "mzTab-ID", null, null, null), 7, 8))));
        assertOneProblemNaming("header-without-column", TourSteps.problems(FILE, tour(
            step("header-without-column", "summary", table("summary", null, null, true), 7, 8))));
    }

    @Test
    void acceptsNullTarget() {
        assertEquals(List.of(), TourSteps.problems(FILE, tour(step("no-target", "overview", null, 1, 1))));
    }
}
