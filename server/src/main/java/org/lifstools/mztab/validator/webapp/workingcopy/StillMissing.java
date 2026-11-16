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
import org.lifstools.mztab.validator.engine.api.FieldRule;

/**
 * The MUST and SHOULD rules of a copy's repository rules that its text does not meet yet
 * ({@link WorkingCopyService#stillMissing}).
 *
 * @param revision the revision of the text the rules were checked against
 * @param checked whether the engine could read the text into a model and so check the rules against it. If not,
 * {@code missing} holds every MUST and SHOULD rule, whose presence is unknown ({@code present} null).
 * @param missing the MUST rules, then the SHOULD rules, each in the order of the profile's mapping: if
 * {@code checked}, those whose field is absent ({@code present} false); a rule whose presence could not be decided is
 * not listed
 */
public record StillMissing(long revision, boolean checked, List<FieldRule> missing) {

    public StillMissing {
        missing = List.copyOf(missing);
    }
}
