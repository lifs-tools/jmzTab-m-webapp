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
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.DeleteLine;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * Error-1012 and Error-1013 for a metadata value that is only {@code [, , , ]}
 * or a list of those: the line is deleted. A value with any content (such as
 * {@code [MS, MS:1, , ]}) is not touched.
 */
final class RemoveEmptyParameter implements QuickFix {

    static final String ID = "remove-empty-parameter";
    private static final String EMPTY = "\\[\\s*,\\s*,\\s*,\\s*\\]";
    private static final Pattern EMPTY_LIST = Pattern.compile("\\s*" + EMPTY + "(\\s*\\|\\s*" + EMPTY + ")*\\s*");
    private static final Pattern VALUE = Pattern.compile("value \"(.*)\" is not a valid");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Remove the line with the empty parameter";
    }

    @Override
    public Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db) {
        if (!Ops.hasCode(m, 1012, 1013)) {
            return Optional.empty();
        }
        Optional<String> quoted = Ops.find(m, VALUE);
        if (quoted.isEmpty() || !EMPTY_LIST.matcher(quoted.get()).matches()) {
            return Optional.empty();
        }
        Optional<Line> line = Ops.lineOf(m, db);
        if (line.isEmpty() || !"MTD".equals(line.get().section()) || line.get().cells().size() < 3
            || !EMPTY_LIST.matcher(line.get().cells().get(2)).matches()) {
            return Optional.empty();
        }
        return Optional.of(List.of(new DeleteLine(line.get().number())));
    }

    @Override
    public boolean appliesToAll() {
        return true;
    }
}
