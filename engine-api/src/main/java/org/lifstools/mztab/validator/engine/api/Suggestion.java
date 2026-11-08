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

/**
 * One line an output mapping writes, and what it does to the working copy.
 *
 * @param key the metadata key (e.g. {@code sample[2]-species[1]}); for a table, its header prefix (e.g.
 *     {@code SMH}) or a row as {@code <section>[<row>]} (e.g. {@code SML[3]})
 * @param value the metadata value; for a table, the cells after the prefix, joined with tabs
 * @param state what applying it does
 */
public record Suggestion(String key, String value, SuggestionState state) {
}
