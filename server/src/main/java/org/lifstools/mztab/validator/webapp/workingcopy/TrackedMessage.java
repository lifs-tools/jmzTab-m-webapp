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

import org.lifstools.mztab.validator.engine.api.ValidationMessage;

/**
 * A message of a working copy's validation, compared with the run before it
 * ({@link MessageDiff}).
 *
 * @param state {@link State#NEW} if the run before did not report it,
 * {@link State#OPEN} if it did
 */
public record TrackedMessage(ValidationMessage message, State state) {

    public enum State {
        NEW, OPEN
    }
}
