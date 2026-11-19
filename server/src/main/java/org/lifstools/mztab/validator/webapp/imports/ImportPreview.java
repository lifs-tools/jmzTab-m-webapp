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
package org.lifstools.mztab.validator.webapp.imports;

import java.util.List;
import org.lifstools.mztab.validator.engine.api.Suggestion;
import org.lifstools.mztab.validator.webapp.workingcopy.EditBatch;

/**
 * What an import would do to a working copy; nothing is stored. Post the {@code batch} (with a change id and the
 * copy's generation) to the edits endpoint to apply it.
 *
 * @param kind the kind of the import
 * @param revision the revision of the copy the preview is for
 * @param mapping the output mapping that was applied
 * @param mappingVersion the version of that mapping
 * @param suggestions what the mapping suggests, e.g. values to review
 * @param notes the mapping's notes
 * @param batch the edits at {@code revision}, without change id and generation
 */
public record ImportPreview(String kind, long revision, String mapping, String mappingVersion,
    List<Suggestion> suggestions, List<String> notes, EditBatch batch) {
}
