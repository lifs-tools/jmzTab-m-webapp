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
package org.lifstools.mztab.validator.webapp.workingcopy;

import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Operations applied in order, as one change, to the given revision of a copy.
 *
 * @param revision the revision the caller saw; must be the current one
 * @param operations at most 5,000
 * @param changeId the caller's id of this change, kept with the copy ({@link WorkingCopyInfo#lastChangeId}) when the
 * batch is applied, so a caller that lost the answer can tell whether this very batch was applied; optional
 */
public record EditBatch(long revision, List<EditOperation> operations, @Nullable UUID changeId) {

    public EditBatch(long revision, List<EditOperation> operations) {
        this(revision, operations, null);
    }
}
