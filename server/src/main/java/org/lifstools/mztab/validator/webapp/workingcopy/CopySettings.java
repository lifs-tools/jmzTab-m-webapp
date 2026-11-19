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

import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.webapp.session.ProfileRef;

/**
 * How a copy is validated: its repository rules (the validation profile), level and error limit. An upload copy
 * takes them from its validation session; a new copy keeps them in its own meta ({@link WorkingCopyService#settings}).
 *
 * @param profile the rules of a CV check
 * @param origin {@value #UPLOAD} or {@value #NEW}
 */
public record CopySettings(ProfileRef profile, ValidationLevel level, int maxErrors, String origin) {

    /** A copy of an uploaded file, made from its validation session. */
    public static final String UPLOAD = "upload";
    /** A new file, made from the engine's skeleton, with no validation session. */
    public static final String NEW = "new";

    /** The level of a new copy: the validator form's default. */
    public static final ValidationLevel DEFAULT_LEVEL = ValidationLevel.INFO;
    /** The error limit of a new copy: the validator form's default. */
    public static final int DEFAULT_MAX_ERRORS = 100;
}
