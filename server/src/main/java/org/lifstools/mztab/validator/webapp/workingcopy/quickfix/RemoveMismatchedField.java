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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.DeleteColumn;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.DeleteLine;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * Info-2057: the file has a field or column that the declared profile has no section for. The name comes from
 * the quoted text before {@code is present}. A metadata field of that name is removed by deleting its line; a
 * column of that name is removed from the header and every row of its table (the first header line of the table,
 * where the engine locates the message). The fix is offered only if the name is found exactly once, in the
 * metadata keys and the table headers together: with more than one place (for example the same column name in
 * two tables), the message does not say which one it means. A name that is no key or column but the group of
 * indexed keys ({@code database} for {@code database[1]}, {@code database[1]-prefix}, ...) removes every line of
 * the group, from the bottom up; with a key or column of the same name as well, the fix is withheld.
 */
final class RemoveMismatchedField implements QuickFix {

    static final String ID = "remove-mismatched-field";
    private static final Pattern NAME = Pattern.compile("^\\s*\"(.+?)\" is present");
    private static final List<String> HEADERS = List.of("SMH", "SFH", "SEH");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Remove the field or column that the profile has no section for";
    }

    @Override
    public Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db) {
        if (!Ops.hasCode(m, 2057)) {
            return Optional.empty();
        }
        String name = Ops.find(m, NAME).orElse(null);
        if (name == null || !Ops.cellSafe(name)) {
            return Optional.empty();
        }
        List<EditOperation> found = new ArrayList<>();
        List<Long> group = new ArrayList<>();
        for (Line l : db.linesOf("MTD", "MTD")) {
            if (l.cells().size() < 2) {
                continue;
            }
            String key = l.cells().get(1);
            if (key.equals(name)) {
                found.add(new DeleteLine(l.number()));
            }
            int bracket = key.indexOf('[');
            if (bracket > 0 && key.substring(0, bracket).equals(name)) {
                group.add(l.number());
            }
        }
        for (String header : HEADERS) {
            Optional<Line> h = db.firstOf(header);
            if (h.isPresent()) {
                for (int i = 1; i < h.get().cells().size(); i++) {
                    if (h.get().cells().get(i).equals(name)) {
                        found.add(new DeleteColumn(header, name, i));
                    }
                }
            }
        }
        if (!group.isEmpty()) {
            if (!found.isEmpty()) {
                return Optional.empty();
            }
            List<EditOperation> lines = new ArrayList<>();
            for (int i = group.size() - 1; i >= 0; i--) {
                lines.add(new DeleteLine(group.get(i)));
            }
            return Optional.of(lines);
        }
        return found.size() == 1 ? Optional.of(found) : Optional.empty();
    }

    @Override
    public boolean appliesToAll() {
        return true;
    }
}
