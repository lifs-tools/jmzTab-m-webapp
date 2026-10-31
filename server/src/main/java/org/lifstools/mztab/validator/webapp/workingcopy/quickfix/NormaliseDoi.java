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
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation.SetMetadataValue;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * Error-1014: in the {@code |}-separated value of a metadata line, every item
 * that starts with {@code doi:} loses repeated {@code doi:} prefixes and a
 * {@code https://doi.org/} (or {@code dx.doi.org}) address in front of the
 * DOI. Other items stay as they are. No change means no fix.
 */
final class NormaliseDoi implements QuickFix {

    static final String ID = "normalise-doi";
    private static final Pattern DOI_ITEM = Pattern.compile("(?i)\\s*doi:.*");
    private static final Pattern PREFIX = Pattern.compile("(?i)^(?:doi:|https?://(?:dx\\.)?doi\\.org/)\\s*");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Write the DOI as doi:10.xxxx/yyyy";
    }

    @Override
    public Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db) {
        Optional<Line> line = Ops.lineOf(m, db);
        if (line.isEmpty() || !"MTD".equals(line.get().section()) || line.get().cells().size() < 3) {
            return Optional.empty();
        }
        String value = line.get().cells().get(2);
        String fixed = normalise(value);
        if (fixed.equals(value)) {
            return Optional.empty();
        }
        return Optional.of(List.of(new SetMetadataValue(line.get().number(), fixed)));
    }

    static String normalise(String value) {
        List<String> items = new ArrayList<>();
        for (String item : value.split("\\|", -1)) {
            if (!DOI_ITEM.matcher(item).matches()) {
                items.add(item);
                continue;
            }
            String doi = item.strip();
            String stripped;
            while (!(stripped = PREFIX.matcher(doi).replaceFirst("")).equals(doi)) {
                doi = stripped;
            }
            items.add("doi:" + doi);
        }
        return String.join("|", items);
    }

    @Override
    public boolean appliesToAll() {
        return true;
    }
}
