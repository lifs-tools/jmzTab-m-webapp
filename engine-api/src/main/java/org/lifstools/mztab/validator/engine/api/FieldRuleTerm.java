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
 * A controlled vocabulary term a {@link FieldRule} accepts.
 *
 * @param accession the term's accession, e.g. {@code MS:1001833}
 * @param name the term's name
 * @param allowChildren whether the children of the term are accepted too
 * @param useTerm whether the term itself is accepted
 */
public record FieldRuleTerm(String accession, String name, boolean allowChildren, boolean useTerm) {
}
