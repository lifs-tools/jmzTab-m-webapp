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
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.SplitCells;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * Error-1008 for a metadata line that has only one cell after the prefix,
 * holding a key and a value separated by spaces (empty cells after it, as spreadsheets
 * write them, are allowed): the cell is split at its
 * first run of spaces.
 */
final class SplitMtdLine implements QuickFix {

    static final String ID = "split-mtd-line";
    private static final Pattern SPLITTABLE = Pattern.compile("^ *[^ ]+ +[^ ].*$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Split the line into key and value";
    }

    @Override
    public Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db) {
        Optional<Line> line = Ops.lineOf(m, db);
        if (line.isEmpty() || !"MTD".equals(line.get().section()) || line.get().cells().size() < 2
            || !line.get().cells().subList(2, line.get().cells().size()).stream().allMatch(String::isEmpty)
            || !SPLITTABLE.matcher(line.get().cells().get(1)).matches()) {
            return Optional.empty();
        }
        return Optional.of(List.of(new SplitCells(line.get().number())));
    }

    @Override
    public boolean appliesToAll() {
        return true;
    }
}
