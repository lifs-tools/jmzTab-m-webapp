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
 * What to read from a table section.
 *
 * @param page 0-based page number
 * @param size rows per page, 1 to 200
 * @param sort the column to sort by, or {@code null} for file order
 * @param filters all must match; applied before paging
 * @param columns the columns to return, in this order; empty means the first 50
 * @param rawFilters unresolved {@code COLUMN:text} filters; the column is the
 * first prefix, split at a {@code :}, that exactly names a column of the
 * table (column names may contain {@code :}), also applied
 */
public record TableQuery(int page, int size, @Nullable String sort, boolean descending,
    List<ColumnFilter> filters, List<String> columns, List<String> rawFilters) {

    public TableQuery {
        filters = filters == null ? List.of() : filters;
        columns = columns == null ? List.of() : columns;
        rawFilters = rawFilters == null ? List.of() : rawFilters;
    }

    public TableQuery(int page, int size, @Nullable String sort, boolean descending,
        List<ColumnFilter> filters, List<String> columns) {
        this(page, size, sort, descending, filters, columns, List.of());
    }
}
