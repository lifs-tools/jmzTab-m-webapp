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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/** Small helpers the fixes share. */
final class Ops {

    private Ops() {
    }

    /** The line a message names, if it is a line of the copy. */
    static Optional<Line> lineOf(ValidationMessage m, WorkingCopyDb db) {
        Long n = m.getLineNumber();
        return n == null || n < 1 ? Optional.empty() : db.line(n);
    }

    /** The first group of the pattern in the message text. */
    static Optional<String> find(ValidationMessage m, Pattern p) {
        if (m.getMessage() == null) {
            return Optional.empty();
        }
        Matcher matcher = p.matcher(m.getMessage());
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Whether the text can be a cell or key the webapp writes. */
    static boolean cellSafe(String s) {
        return s != null && !s.isBlank() && s.length() <= 1000 && s.indexOf('\t') < 0 && s.indexOf('\r') < 0
            && s.indexOf('\n') < 0;
    }
}
