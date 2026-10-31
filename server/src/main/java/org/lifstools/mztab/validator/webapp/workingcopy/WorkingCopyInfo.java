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

import java.time.Instant;
import java.util.UUID;
import org.lifstools.mztab.validator.webapp.session.SectionsSummary;
import org.springframework.lang.Nullable;

/**
 * A working copy as the editor sees it.
 *
 * @param id the copy's id, which is the id of the validation it was made from
 * @param fileName the name of the uploaded file
 * @param revision 0 after creation; every edit, undo and redo adds one
 * @param mzTabVersion the version the last ended run validated the text as (the
 * engine's report, else the declared version); before any run, the session's;
 * null if none is known
 * @param sections the sizes of the text's sections, by line prefix: MTD lines,
 * and for each table the rows and the cells of its first header line after the
 * prefix (null without a header line)
 * @param expiresAt when the copy is deleted unless it changes again
 * @param validation the running or last ended validation run; null if none
 */
public record WorkingCopyInfo(UUID id, String fileName, long revision, @Nullable String mzTabVersion, long lineCount,
    SectionsSummary sections, boolean canUndo, boolean canRedo, Instant expiresAt,
    @Nullable WorkingCopyValidation validation) {
}
