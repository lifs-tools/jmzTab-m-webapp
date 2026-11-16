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

import java.util.List;

/**
 * The rules of a CV mapping, with, if a text was given, whether the text meets each of them.
 *
 * @param parsed whether a text was given and read into a model; if not, no rule has a presence
 * @param rules the rules, in the order of the mapping
 */
public record FieldRules(boolean parsed, List<FieldRule> rules) {

    public FieldRules {
        rules = List.copyOf(rules);
    }
}
