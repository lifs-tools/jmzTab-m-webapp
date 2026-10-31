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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.DeleteLine;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.RenameMetadataKey;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * Warn-3014, {@code The CV with prefix "P" was defined but never used}: the
 * {@code cv[n]-*} lines of the CV whose {@code cv[n]-label} is P are deleted
 * and the keys of the higher {@code cv[m]-*} lines are renumbered to
 * {@code cv[m-1]-*}. The renames come first (they do not move lines), then the
 * deletes from the bottom up, so every operation sees the numbers it names.
 */
final class RemoveUnusedCv implements QuickFix {

    static final String ID = "remove-unused-cv";
    private static final Pattern PREFIX = Pattern.compile("CV with prefix \"(.+?)\" was defined");
    private static final Pattern KEY = Pattern.compile("^cv\\[(\\d+)\\]-(.+)$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Remove the unused ontology declaration";
    }

    @Override
    public Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db) {
        if (!Ops.hasCode(m, 3014)) {
            return Optional.empty();
        }
        Optional<String> prefix = Ops.find(m, PREFIX);
        if (prefix.isEmpty()) {
            return Optional.empty();
        }
        List<Line> mtd = db.linesOf("MTD", "MTD");
        int index = -1;
        for (Line l : mtd) {
            Matcher k = key(l);
            if (k != null && k.group(2).equals("label") && l.cells().size() > 2
                && l.cells().get(2).equals(prefix.get())) {
                index = Integer.parseInt(k.group(1));
                break;
            }
        }
        if (index < 0) {
            return Optional.empty();
        }
        List<EditOperation> renames = new ArrayList<>();
        List<Long> deletes = new ArrayList<>();
        for (Line l : mtd) {
            Matcher k = key(l);
            if (k == null) {
                continue;
            }
            int n = Integer.parseInt(k.group(1));
            if (n == index) {
                deletes.add(l.number());
            } else if (n > index) {
                renames.add(new RenameMetadataKey(l.number(), "cv[" + (n - 1) + "]-" + k.group(2)));
            }
        }
        List<EditOperation> ops = new ArrayList<>(renames);
        for (int i = deletes.size() - 1; i >= 0; i--) {
            ops.add(new DeleteLine(deletes.get(i)));
        }
        return Optional.of(ops);
    }

    private static Matcher key(Line l) {
        if (l.cells().size() < 2) {
            return null;
        }
        Matcher k = KEY.matcher(l.cells().get(1));
        return k.matches() && k.group(1).length() <= 6 ? k : null;
    }

    @Override
    public boolean appliesToAll() {
        return false;
    }
}
