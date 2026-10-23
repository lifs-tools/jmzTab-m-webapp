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
package org.lifstools.mztab.validator.webapp.sections;

import java.util.UUID;
import org.lifstools.mztab.validator.webapp.session.SectionsSummary;
import org.lifstools.mztab2.model.MzTab;
import org.springframework.stereotype.Component;

/**
 * Writes the sections of a parsed file to the session's storage.
 * Placeholder: the section store follows in a later change.
 */
@Component
public class SectionWriter {

    /**
     * @return the sizes of the written sections; {@code null} until the
     * section store exists
     */
    public SectionsSummary write(UUID id, MzTab mzTab) {
        return null;
    }
}
