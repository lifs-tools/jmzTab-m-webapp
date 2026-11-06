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
package org.lifstools.mztab2.server.api.validation;

import org.springframework.lang.Nullable;

/**
 * The element of the document viewer a tour step is about, in the shape the viewer highlights a message's location:
 * a metadata {@code group} or {@code key}, or a table row ({@code rowIndex}, the 0-based data row of the tab's
 * section), a cell (with {@code column}) or a column header ({@code column} with {@code header}).
 *
 * @param tab {@code metadata}, {@code summary}, {@code features} or {@code evidence}
 */
public record TourTargetDto(String tab, @Nullable String group, @Nullable String key, @Nullable Integer rowIndex,
    @Nullable String column, @Nullable Boolean header) {

}
