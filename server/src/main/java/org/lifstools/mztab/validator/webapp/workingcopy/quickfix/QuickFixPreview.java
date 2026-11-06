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
import org.lifstools.mztab.validator.webapp.workingcopy.EditBatch;

/**
 * What applying a quick fix would do. The client applies it by posting
 * {@code batch} to the edits endpoint, so a preview of an older revision is
 * refused there.
 *
 * @param revision the revision the preview was made for; equals {@code batch.revision()}
 * @param occurrences how many operations the batch has (all of them, whatever
 * {@code changes} lists)
 * @param changes at most {@value QuickFixes#MAX_CHANGES} changed lines, in line order
 */
public record QuickFixPreview(String fixId, String title, long revision, int occurrences, List<LineChange> changes,
    EditBatch batch) {
}
