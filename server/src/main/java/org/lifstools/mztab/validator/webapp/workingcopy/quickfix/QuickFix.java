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
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;

/**
 * A mechanical, previewed text edit for a common validation problem. A fix
 * reads the message and the lines of the copy it names and returns edit
 * operations; it never parses mzTab-M, and never picks a CV term, unit or
 * value for the user.
 */
public interface QuickFix {

    /** The id the guidance catalog and the REST path use, e.g. {@code remove-empty-parameter}. */
    String id();

    /** What the fix does, in plain words. */
    String title();

    /**
     * @param m a message of the copy's last validation, for the current revision
     * @param db the copy
     * @return the operations that fix this message, in an order in which every
     * operation sees the line numbers it names, or empty if the fix does not
     * apply to this message
     */
    Optional<List<EditOperation>> forMessage(ValidationMessage m, WorkingCopyDb db);

    /**
     * @return whether the fix can be applied to every message of its guidance
     * group in one batch
     */
    boolean appliesToAll();
}
