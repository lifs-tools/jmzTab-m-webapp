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
package org.lifstools.mztab.submission.mapping;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * A mapping file: its identity, the CVs it declares, its named values ({@code let}) and its rules. {@code target} is
 * the mzTab-M version an output mapping writes, and null for an input mapping. An output mapping has
 * {@code metadata} and {@code tables}, an input mapping {@code sheets}; the others are empty. The maps are not to be
 * changed.
 */
public record Mapping(String id, String version, Kind kind, String vocabulary, String target, List<String> parameters,
        LinkedHashMap<String, CvInfo> cvs, LinkedHashMap<String, Expr> let, List<MetadataRule> metadata,
        List<TableRule> tables, List<Sheet> sheets) {

    public Mapping {
        parameters = List.copyOf(parameters);
        metadata = List.copyOf(metadata);
        tables = List.copyOf(tables);
        sheets = List.copyOf(sheets);
    }

    /**
     * A sheet of a workbook an input mapping reads.
     *
     * @param name the sheet's name, or null when {@code namedBy} names it
     * @param namedBy the field ({@code <group>.<field>}) whose value names the sheet, one sheet per record of that
     *     group; null when {@code name} is given
     * @param group the vocabulary group the sheet fills
     * @param layout how the sheet is laid out: a key of the vocabulary's {@code conventions}
     * @param columns the field id each column fills, by the column's id in the sheet; empty when the layout fixes the
     *     sheet's content
     * @param factorColumns the record member that the factor columns fill (convention
     *     {@code samples_factor_columns}), or null
     */
    public record Sheet(String name, String namedBy, String group, String layout, LinkedHashMap<String, String> columns,
            String factorColumns) {
    }

    /** Input format to vocabulary, or vocabulary to output format. */
    public enum Kind {
        INPUT, OUTPUT
    }

    /** A CV the mapping's parameters use: {@code cv[n]-full_name}, {@code -version} and {@code -uri}. */
    public record CvInfo(String fullName, String version, String uri) {
    }

    /** A metadata rule: one line, or lines for each item of a list. */
    public sealed interface MetadataRule {
    }

    /** One metadata line; {@code key} may hold {@code {x#}} placeholders of the enclosing {@link Each} rules. */
    public record Line(String key, Expr value) implements MetadataRule {
    }

    /** The {@code lines} for each item of {@code over} (bound to {@code as}) for which {@code where} (optional) holds. */
    public record Each(Expr over, String as, Expr where, List<MetadataRule> lines) implements MetadataRule {

        public Each {
            lines = List.copyOf(lines);
        }
    }

    /** A table: one row per item of {@code rows} (bound to {@code as}); {@code section} is e.g. {@code SML} and {@code header} {@code SMH}. */
    public record TableRule(String section, String header, Expr rows, String as, List<ColumnRule> columns) {

        public TableRule {
            columns = List.copyOf(columns);
        }
    }

    /** A column rule: one column, or columns for each item of a list. */
    public sealed interface ColumnRule {
    }

    /** A column; when {@code where} (optional) does not hold for the table, the column is left out of the header and every row. */
    public record Column(String header, Expr value, Expr where) implements ColumnRule {
    }

    /** The {@code columns} for each item of {@code over}, bound to {@code as}. */
    public record EachColumn(Expr over, String as, List<Column> columns) implements ColumnRule {

        public EachColumn {
            columns = List.copyOf(columns);
        }
    }
}
