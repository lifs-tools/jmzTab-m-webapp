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
 * The sizes of the sections of a validated file.
 *
 * @param metadataLines the number of metadata lines
 * @param sml the small molecule table
 * @param smf the small molecule feature table
 * @param sme the small molecule evidence table
 */
public record SectionSizes(int metadataLines, TableDims sml, TableDims smf, TableDims sme) {
}
