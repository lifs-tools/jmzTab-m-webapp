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
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.InsertLine;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * Error-2008, {@code "X" MUST be defined in the metadata section}: a line
 * {@code MTD<tab>X<tab>} with an empty value is inserted after the last line
 * of X's group (the lines whose key starts like X up to its first
 * {@code [}), else after the last metadata line. The value is left for the
 * user. A group would need one insert per name, so this fix does not apply
 * to a group.
 */
final class AddMissingMetadata implements QuickFix {

    static final String ID = "add-missing-metadata";
    private static final Pattern NAME = Pattern.compile("^\\s*\"(.+)\" MUST be defined in the metadata section");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Add the missing metadata line";
    }

    @Override
    public Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db) {
        Optional<String> name = Ops.find(m, NAME);
        if (name.isEmpty() || !Ops.cellSafe(name.get()) || !name.get().equals(name.get().strip())) {
            return Optional.empty();
        }
        String x = name.get();
        int bracket = x.indexOf('[');
        String groupPrefix = bracket < 0 ? x : x.substring(0, bracket + 1);
        long lastOfGroup = 0;
        long lastMtd = 0;
        for (Line l : db.linesOf("MTD", "MTD")) {
            lastMtd = l.number();
            if (l.cells().size() > 1 && l.cells().get(1).startsWith(groupPrefix)) {
                lastOfGroup = l.number();
            }
        }
        long after = lastOfGroup > 0 ? lastOfGroup : lastMtd;
        return Optional.of(List.of(new InsertLine(after + 1, "MTD\t" + x + "\t")));
    }

    @Override
    public boolean appliesToAll() {
        return false;
    }
}
