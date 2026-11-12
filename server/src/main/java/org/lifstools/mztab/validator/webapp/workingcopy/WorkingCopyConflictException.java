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
 * The request does not fit the copy's current state: there is nothing to undo
 * or redo, or the JSON export finds no valid current state. The REST API
 * answers 409; it is an {@link IllegalStateException} so callers that only
 * know the state rule can catch that.
 */
public class WorkingCopyConflictException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public WorkingCopyConflictException(String message) {
        super(message);
    }
}
