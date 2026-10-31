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
import static org.junit.jupiter.api.Assertions.assertSame;

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
}
