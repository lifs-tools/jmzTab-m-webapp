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

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One rule of a CV mapping: a field of the mzTab-M model that takes terms.
 *
 * @param id the rule's id in the mapping
 * @param name the rule's name; its id if the mapping gives none
 * @param path the JXPath of the field in the model, e.g. {@code /metadata/@quantificationMethod}
 * @param requirement {@code MUST}, {@code SHOULD} or {@code MAY}, as in the mapping
 * @param logic how the terms combine: {@code AND}, {@code OR} or {@code XOR}
 * @param terms the terms the rule accepts
 * @param present whether the text has the field; {@code null} if no text was given or the text could not be read
 *     into a model
 */
public record FieldRule(String id, String name, String path, String requirement, String logic,
    List<FieldRuleTerm> terms, @Schema(nullable = true) Boolean present) {

    public FieldRule {
        terms = List.copyOf(terms);
    }
}
