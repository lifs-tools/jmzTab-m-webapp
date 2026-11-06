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
import java.util.UUID;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.guidance.Guidance;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyConflictException;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation.Status;
import org.springframework.stereotype.Service;

/**
 * Previews quick fixes for the messages of a working copy's last validation.
 * Read-only: neither the text, the revision nor the history change.
 */
@Service
public class QuickFixService {

    private final WorkingCopyService copies;

    public QuickFixService(WorkingCopyService copies) {
        this.copies = copies;
    }

    /**
     * @param fixId the fix
     * @param message an index into the messages of the copy's last validation
     * @param all whether to fix every message of the guidance group that holds {@code message}
     * @throws IllegalArgumentException (400) for an unknown fix id, a fix that is not a text edit, an
     * index out of range, a fix that does not apply, or a group that is too large
     * @throws WorkingCopyConflictException (409) if the last validation is not of the current
     * revision, not finished or out of date
     */
    public QuickFixPreview preview(UUID id, String fixId, int message, boolean all) {
        if (!fixId.matches("[a-z0-9-]{1,64}")) {
            throw new IllegalArgumentException("fixId is not a quick fix.");
        }
        if (QuickFixes.REENCODE_LATIN1.equals(fixId)) {
            throw new IllegalArgumentException("This fix is not a text edit; re-create the copy with "
                + "encoding=WINDOWS_1252.");
        }
        QuickFix fix = QuickFixes.find(fixId)
            .orElseThrow(() -> new IllegalArgumentException("There is no quick fix " + fixId + "."));
        WorkingCopyValidation v = copies.info(id).validation();
        if (v == null || v.status() != Status.DONE || v.stale()) {
            throw new WorkingCopyConflictException("Validate the current revision first.");
        }
        if (message < 0 || message >= v.messages().size()) {
            throw new IllegalArgumentException("message must be between 0 and " + (v.messages().size() - 1) + ".");
        }
        ValidationMessage chosen = v.messages().get(message).message();
        List<ValidationMessage> group = null;
        if (all) {
            group = new ArrayList<>();
            for (Guidance g : v.guidance()) {
                if (g.messages().contains(message)) {
                    for (int i : g.messages()) {
                        group.add(v.messages().get(i).message());
                    }
                    break;
                }
            }
            if (group.isEmpty()) {
                group.add(chosen);
            }
        }
        List<ValidationMessage> finalGroup = group;
        return copies.read(id, db -> {
            if (db.revision() != v.revision()) {
                throw new WorkingCopyConflictException("Validate the current revision first.");
            }
            return QuickFixes.preview(fix, v.revision(), chosen, finalGroup, db);
        });
    }
}
