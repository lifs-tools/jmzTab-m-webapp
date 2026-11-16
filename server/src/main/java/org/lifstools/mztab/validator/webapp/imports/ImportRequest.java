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

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

/**
 * A file to preview against a working copy.
 *
 * @param kind what the instance is; only {@value ImportService#VOCABULARY_INSTANCE} today
 * @param revision the copy's revision the preview is for; another one is refused with 409
 * @param instance the vocabulary instance (JSON)
 * @param datasetIndex the 1-based position of the dataset to import; may be left out if the instance has one
 * @param studyId the study id the mzTab-ID is made from (e.g. LCS-00123)
 * @param keepExisting whether lines the copy already has are kept instead of refused as a conflict
 */
public record ImportRequest(String kind, long revision, JsonNode instance, @Nullable Integer datasetIndex,
    String studyId, boolean keepExisting) {
}
