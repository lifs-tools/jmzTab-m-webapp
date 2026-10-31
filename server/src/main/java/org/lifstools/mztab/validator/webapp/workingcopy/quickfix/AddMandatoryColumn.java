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
import java.util.Set;
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.InsertColumn;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * Error-1004: the named column is inserted into the header and every row of
 * the table, with the cell text {@code null}, before the first
 * {@code abundance_} or {@code opt_} column (else at the end). The column name
 * comes from the message's column location, else from its text. The engine
 * reports one missing column at a time, so this fix does not apply to a group.
 */
final class AddMandatoryColumn implements QuickFix {

    static final String ID = "add-mandatory-column";
    private static final Pattern NAME = Pattern.compile("Mandatory column \"(.+?)\" is required");
    private static final Set<String> HEADERS = Set.of("SMH", "SFH", "SEH");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Add the missing column";
    }

    @Override
    public Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db) {
        String name = m.getLocation() != null && m.getLocation().getColumn() != null ? m.getLocation().getColumn()
            : Ops.find(m, NAME).orElse(null);
        Optional<Line> header = Ops.lineOf(m, db);
        if (name == null || !Ops.cellSafe(name) || header.isEmpty() || !HEADERS.contains(header.get().section())
            || header.get().cells().contains(name)) {
            return Optional.empty();
        }
        List<String> cells = header.get().cells();
        int position = cells.size();
        for (int i = 1; i < cells.size(); i++) {
            if (cells.get(i).startsWith("abundance_") || cells.get(i).startsWith("opt_")) {
                position = i;
                break;
            }
        }
        return Optional.of(List.of(new InsertColumn(header.get().section(), position, name, "null")));
    }

    @Override
    public boolean appliesToAll() {
        return false;
    }
}
