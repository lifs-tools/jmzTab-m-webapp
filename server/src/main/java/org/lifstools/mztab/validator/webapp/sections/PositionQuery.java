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
package org.lifstools.mztab.validator.webapp.sections;

import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Columns of a working copy's table named by position: the cell index in the line, where the prefix is cell 0,
 * so the first column is 1. Positions tell apart columns of the same name. Each part replaces its by-name
 * counterpart in {@link TableQuery} and cannot be combined with it.
 *
 * @param positions the columns to return, in this order; {@code null} for the by-name columns or the default
 * @param sortPosition the column to sort by; {@code null} for the by-name sort or none
 * @param filters {@code POSITION:text} filters, all applied with the by-name ones
 */
public record PositionQuery(@Nullable List<Integer> positions, @Nullable Integer sortPosition, List<String> filters) {

    /** Nothing by position: the page still reports the positions of its columns. */
    public static final PositionQuery NONE = new PositionQuery(null, null, List.of());

    public PositionQuery {
        filters = filters == null ? List.of() : filters;
    }
}
