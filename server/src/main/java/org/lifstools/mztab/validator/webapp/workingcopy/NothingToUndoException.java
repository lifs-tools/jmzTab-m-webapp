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

/**
 * An undo or redo with nothing to undo or redo (a second queued undo, or another tab's). The REST API answers 409
 * with the error code {@code nothing_to_undo}.
 */
public class NothingToUndoException extends WorkingCopyConflictException {

    private static final long serialVersionUID = 1L;

    public NothingToUndoException(boolean undo) {
        super(undo ? "There is nothing to undo." : "There is nothing to redo.");
    }
}
