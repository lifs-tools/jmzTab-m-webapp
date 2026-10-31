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
package org.lifstools.mztab.validator.webapp.workingcopy.quickfix;

import java.util.List;
import java.util.Optional;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.JoinWithPrevious;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.SetRawLine;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * Error-1000 for a line without a line type that continues the line above it:
 * the line is appended to the one above, with a space where the line break
 * was (the guidance promises a space) unless one of the two sides already has
 * whitespace there. Two operations: {@code SetRawLine} adds the space,
 * {@code JoinWithPrevious} joins.
 */
final class JoinBrokenLine implements QuickFix {

    static final String ID = "join-broken-line";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Join the line with the one above";
    }

    @Override
    public Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db) {
        if (!Ops.hasCode(m, 1000)) {
            return Optional.empty();
        }
        Optional<Line> line = Ops.lineOf(m, db);
        if (line.isEmpty() || line.get().number() < 2 || !"?".equals(line.get().section())) {
            return Optional.empty();
        }
        Optional<Line> previous = db.line(line.get().number() - 1);
        if (previous.isEmpty()) {
            return Optional.empty();
        }
        String text = line.get().text();
        String before = previous.get().text();
        boolean spaced = text.isEmpty() || before.isEmpty() || Character.isWhitespace(text.charAt(0))
            || Character.isWhitespace(before.charAt(before.length() - 1));
        long n = line.get().number();
        return Optional.of(spaced ? List.of(new JoinWithPrevious(n))
            : List.of(new SetRawLine(n, " " + text), new JoinWithPrevious(n)));
    }

    @Override
    public boolean appliesToAll() {
        return true;
    }
}
