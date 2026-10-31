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
package org.lifstools.mztab.validator.webapp.workingcopy.quickfix;

import org.springframework.lang.Nullable;

/**
 * One line of a preview. The texts are the cells joined by tabs, without the
 * line ending.
 *
 * @param lineNumber the line in the copy now; for an insert, the number the
 * new line will have
 * @param before null for an insert
 * @param after null for a delete
 */
public record LineChange(long lineNumber, @Nullable String before, @Nullable String after) {
}
