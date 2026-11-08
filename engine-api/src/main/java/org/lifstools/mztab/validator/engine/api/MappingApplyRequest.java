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
package org.lifstools.mztab.validator.engine.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Applies an output mapping of the submission vocabulary to the text of a working copy.
 *
 * @param mapping the output mapping id, e.g. {@code mztab-2.0.0-M}
 * @param mappingVersion its version, e.g. {@code 1.0.0}
 * @param parameters the mapping's parameters, e.g. {@code study_id}
 * @param instance the vocabulary instance (JSON)
 * @param text the working copy's text; empty for a new file
 * @param provenance whether the webapp, the vocabulary and the mapping are recorded as a {@code software[n]} entry
 */
public record MappingApplyRequest(String mapping, String mappingVersion, Map<String, String> parameters,
    JsonNode instance, String text, boolean provenance) {
}
