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
package org.lifstools.mztab.validator.webapp.workingcopy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.engine.api.ValidationLocation;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.TrackedMessage.State;

class MessageDiffTest {

    private static ValidationMessage msg(String code, String text, long line) {
        ValidationMessage m = new ValidationMessage();
        m.setCode(code.isEmpty() ? "" : "[" + code + "] " + text);
        m.setMessage(text);
        m.setLineNumber(line);
        return m;
    }

    private static ValidationMessage atKey(String code, String key, long line) {
        ValidationMessage m = msg(code, "about " + key, line);
        ValidationLocation l = new ValidationLocation();
        l.setSection(ValidationLocation.Section.MTD);
        l.setMetadataKey(key);
        m.setLocation(l);
        return m;
    }

    private static ValidationMessage atRow(String code, int row, String column, long line) {
        ValidationMessage m = msg(code, "row " + row, line);
        ValidationLocation l = new ValidationLocation();
        l.setSection(ValidationLocation.Section.SML);
        l.setRowIndex(row);
        l.setColumn(column);
        m.setLocation(l);
        return m;
    }

    @Test
    void fixedNewAndOpen() {
        ValidationMessage a = atKey("Error-1012", "x", 3);
        ValidationMessage b = msg("Error-2000", "broken", 7);
        ValidationMessage b2 = msg("Error-2000", "broken", 7);
        ValidationMessage c = atRow("Warn-2001", 3, "SML_ID", 60);

        MessageDiff.Result r = MessageDiff.compare(List.of(a, b), List.of(b2, c));

        assertEquals(2, r.current().size());
        assertSame(b2, r.current().get(0).message());
        assertEquals(State.OPEN, r.current().get(0).state());
        assertSame(c, r.current().get(1).message());
        assertEquals(State.NEW, r.current().get(1).state());
        assertEquals(List.of(a), r.fixed());
    }

    private static ValidationMessage atGroup(String code, String group, long line) {
        ValidationMessage m = msg(code, "\"" + group + "\" is present", line);
        ValidationLocation l = new ValidationLocation();
        l.setSection(ValidationLocation.Section.MTD);
        l.setMetadataGroup(group);
        m.setLocation(l);
        return m;
    }

    @Test
    void aGroupThatMovedIsTheSameProblem() {
        // a line above the group was removed: its first line is one less, the problem is the same
        ValidationMessage before = atGroup("Info-2057", "database", 49);
        ValidationMessage after = atGroup("Info-2057", "database", 48);
        ValidationMessage otherGroup = atGroup("Info-2057", "id_confidence_measure", 48);

        MessageDiff.Result r = MessageDiff.compare(List.of(before), List.of(after, otherGroup));

        assertEquals(State.OPEN, r.current().get(0).state());
        assertEquals(State.NEW, r.current().get(1).state());
        assertEquals(List.of(), r.fixed());
    }

    @Test
    void sameCodeOtherPlaceIsNew() {
        // the key takes precedence over the line: the same key on another line is still the same problem
        ValidationMessage keyBefore = atKey("Error-1012", "x", 3);
        ValidationMessage keyAfter = atKey("Error-1012", "x", 4);
        // the same code on another key, row or line is another problem
        ValidationMessage otherKey = atKey("Error-1012", "y", 3);
        ValidationMessage rowBefore = atRow("Warn-2001", 3, "SML_ID", 60);
        ValidationMessage otherRow = atRow("Warn-2001", 4, "SML_ID", 61);
        ValidationMessage lineBefore = msg("Error-2000", "broken", 7);
        ValidationMessage otherLine = msg("Error-2000", "broken", 8);
        // another code at the same place is another problem
        ValidationMessage otherCode = atKey("Error-1013", "x", 3);

        MessageDiff.Result r = MessageDiff.compare(List.of(keyBefore, rowBefore, lineBefore),
            List.of(keyAfter, otherKey, otherRow, otherLine, otherCode));

        assertEquals(List.of(State.OPEN, State.NEW, State.NEW, State.NEW, State.NEW),
            r.current().stream().map(TrackedMessage::state).toList());
        assertEquals(List.of(rowBefore, lineBefore), r.fixed());
    }

    @Test
    void repeatsMatchOneToOneAndTextIsTheLastResort() {
        ValidationMessage noPlace1 = msg("", "The engine is unavailable.", -1);
        ValidationMessage noPlace2 = msg("", "The engine is unavailable.", -1);
        ValidationMessage other = msg("", "Something else.", -1);

        MessageDiff.Result r = MessageDiff.compare(List.of(noPlace1, noPlace2), List.of(noPlace2, other));

        assertEquals(List.of(State.OPEN, State.NEW), r.current().stream().map(TrackedMessage::state).toList());
        assertEquals(1, r.fixed().size());
    }

    /** A message of the CV-rule check, as jmzTab-m gives it: category cross_check. */
    private static ValidationMessage cv(String code, String key, long line) {
        ValidationMessage m = atKey(code, key, line);
        m.setCategory(ValidationMessage.Category.CROSS_CHECK);
        return m;
    }

    @Test
    void cvRuleMessagesAreTheCrossChecks() {
        ValidationMessage note = msg("", "Semantic validation was skipped.", -1);
        note.setCategory(ValidationMessage.Category.CROSS_CHECK);
        ValidationMessage format = msg("Error-1012", "empty", 3);
        format.setCategory(ValidationMessage.Category.FORMAT);
        ValidationMessage logical = msg("Warn-2001", "logical", 4);
        logical.setCategory(ValidationMessage.Category.LOGICAL);

        assertTrue(MessageDiff.isCvRule(cv("Info-3010", "instrument[1]-source", 9)));
        assertTrue(MessageDiff.isCvRule(note), "the engine's notes about the CV check are of it too");
        assertFalse(MessageDiff.isCvRule(format));
        assertFalse(MessageDiff.isCvRule(logical));
        assertFalse(MessageDiff.isCvRule(new ValidationMessage()), "a message without a category is structural");
    }

    @Test
    void aStructuralRunKeepsTheCvMessagesOpenAndFixesNoneOfThem() {
        ValidationMessage a = atKey("Error-1012", "x", 3);
        ValidationMessage b = msg("Error-2000", "broken", 7);
        ValidationMessage a2 = atKey("Error-1012", "x", 3);
        ValidationMessage cv1 = cv("Info-3010", "instrument[1]-source", 9);
        ValidationMessage cv2 = cv("Error-3006", "ms_run[1]-format", 11);

        MessageDiff.Result r = MessageDiff.compareRun(List.of(a, b), List.of(cv1, cv2), List.of(a2), false);

        assertEquals(List.of(a2, cv1, cv2), r.current().stream().map(TrackedMessage::message).toList());
        assertEquals(List.of(State.OPEN, State.OPEN, State.OPEN),
            r.current().stream().map(TrackedMessage::state).toList());
        assertEquals(List.of(b), r.fixed(), "only a structural message is fixed by a structural run");
    }

    @Test
    void aSemanticRunComparesEachKindWithItsOwn() {
        ValidationMessage a = atKey("Error-1012", "x", 3);
        ValidationMessage b = msg("Error-2000", "broken", 7);
        ValidationMessage cv1 = cv("Info-3010", "instrument[1]-source", 9);
        ValidationMessage cv2 = cv("Error-3006", "ms_run[1]-format", 11);
        ValidationMessage a2 = atKey("Error-1012", "x", 3);
        ValidationMessage c = msg("Error-2000", "other", 8);
        ValidationMessage cv1again = cv("Info-3010", "instrument[1]-source", 9);
        ValidationMessage cv3 = cv("Error-3005", "sample[1]-species[1]", 14);

        MessageDiff.Result r = MessageDiff.compareRun(List.of(a, b), List.of(cv1, cv2),
            List.of(a2, cv1again, c, cv3), true);

        assertEquals(List.of(a2, cv1again, c, cv3), r.current().stream().map(TrackedMessage::message).toList(),
            "the run's own order");
        assertEquals(List.of(State.OPEN, State.OPEN, State.NEW, State.NEW),
            r.current().stream().map(TrackedMessage::state).toList());
        assertEquals(List.of(b, cv2), r.fixed());
    }

    @Test
    void aCvMessageIsNotMatchedWithAStructuralOne() {
        // the same code and place in the other kind's list is not the message shown before
        ValidationMessage structural = atKey("Error-3006", "ms_run[1]-format", 11);
        ValidationMessage cvNow = cv("Error-3006", "ms_run[1]-format", 11);

        MessageDiff.Result r = MessageDiff.compareRun(List.of(structural), List.of(), List.of(cvNow), true);

        assertEquals(List.of(State.NEW), r.current().stream().map(TrackedMessage::state).toList());
        assertEquals(List.of(structural), r.fixed());
    }
}
