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

import java.util.List;
import java.util.regex.Pattern;

/**
 * An expression of the mapping language, one record per form of {@code schema/mapping-language.md}. Optional parts
 * are null when the mapping leaves them out.
 */
public sealed interface Expr {

    /** {@code literal}: a fixed value. */
    record Literal(Value value) implements Expr {
    }

    /** {@code field: a.b.c}: a path whose first segment is a scope variable, {@code params} or a top-level instance key. */
    record Field(List<String> path) implements Expr {

        public Field {
            path = List.copyOf(path);
        }
    }

    /** {@code ref}: the value of a {@code let} name. */
    record Ref(String name) implements Expr {
    }

    /** {@code pos}: the 1-based position of a scope variable among the items its {@code each}, {@code map}, {@code find} or {@code filter} visits. */
    record Pos(String variable) implements Expr {
    }

    /** {@code format}: a template with placeholders {@code {0}} and {@code {1:02}}, parsed into parts. */
    record Format(List<Part> parts, List<Expr> args) implements Expr {

        public Format {
            parts = List.copyOf(parts);
            args = List.copyOf(args);
        }

        /** A piece of a template: a literal text, or an argument with an optional width. */
        public sealed interface Part {
        }

        /** Literal template text. */
        public record Chunk(String text) implements Part {
        }

        /** Argument {@code index}; {@code width} is 0 without a width, and {@code zeros} pads with zeros instead of spaces. */
        public record Placeholder(int index, int width, boolean zeros) implements Part {
        }
    }

    /** {@code join}: the items, lists among them spread, rendered as text and joined with {@code sep}; empty items are skipped. */
    record Join(List<Expr> items, String sep) implements Expr {

        public Join {
            items = List.copyOf(items);
        }
    }

    /** {@code first}: the first item that is not empty. */
    record First(List<Expr> items) implements Expr {

        public First {
            items = List.copyOf(items);
        }
    }

    /** {@code split}: the text split at {@code sep}, the parts stripped, empty parts dropped. */
    record Split(Expr value, String sep) implements Expr {
    }

    /** {@code when}: {@code then} when {@code condition} is not empty, else {@code otherwise} (Null when absent). */
    record When(Expr condition, Expr then, Expr otherwise) implements Expr {
    }

    /** {@code eq}: the two values compared as text, case-sensitive. */
    record Eq(Expr left, Expr right) implements Expr {
    }

    /** {@code lower}: lower case. */
    record Lower(Expr value) implements Expr {
    }

    /** {@code not-empty}: a test. */
    record NotEmpty(Expr value) implements Expr {
    }

    /** {@code count}: the size of a list. */
    record Count(Expr list) implements Expr {
    }

    /** {@code at}: the element at a 1-based index, or Null when out of range. */
    record At(Expr list, Expr index) implements Expr {
    }

    /** {@code concat}: the lists joined into one. */
    record Concat(List<Expr> lists) implements Expr {

        public Concat {
            lists = List.copyOf(lists);
        }
    }

    /** {@code entries}: an object as a list of {@code {key, value}} objects. */
    record Entries(Expr object) implements Expr {
    }

    /** {@code map}: {@code value} for each item of {@code over}, the item bound to {@code as}. */
    record Map(Expr over, String as, Expr value) implements Expr {
    }

    /** {@code find}: the first item of {@code in} for which {@code where} holds, or Null. */
    record Find(Expr in, String as, Expr where) implements Expr {
    }

    /** {@code filter}: every item of {@code in} for which {@code where} holds. */
    record Filter(Expr in, String as, Expr where) implements Expr {
    }

    /** {@code term}: a CV dict, a text with an agreed encoding, or other text as a parameter. */
    record Term(Expr value, Expr paramValue) implements Expr {
    }

    /** {@code cvparam}: a CV parameter from its parts. */
    record CvParam(Expr cv, Expr accession, Expr name, Expr value) implements Expr {
    }

    /** {@code user-param}: a user parameter. */
    record UserParam(Expr name, Expr value) implements Expr {
    }

    /** {@code label}: the CV name, or the text, or the number as Python writes it. */
    record Label(Expr value) implements Expr {
    }

    /** {@code number}: the number as Python writes it, or the text {@code null} for Null. */
    record Number(Expr value) implements Expr {
    }

    /** {@code normalise}: a DOI or an e-mail address checked with {@link Identifiers}. */
    record Normalise(Identifier kind, Expr value) implements Expr {
    }

    /** What {@link Normalise} checks. */
    enum Identifier {
        DOI, EMAIL
    }

    /** {@code match}: the named group of the first match of {@code regex}, or Null. */
    record Match(Expr value, Pattern regex, String group) implements Expr {

        @Override
        public boolean equals(Object o) {
            return o instanceof Match m && value.equals(m.value) && regex.pattern().equals(m.regex.pattern()) && group.equals(m.group);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(value, regex.pattern(), group);
        }
    }

    /** {@code convert-unit}: the value multiplied by the published factor from one unit to another. */
    record ConvertUnit(Expr value, Expr from, Expr to) implements Expr {
    }

    /** {@code aggregate}: the mean or the coefficient of variation of the numbers of a list. */
    record Aggregate(AggregateFunction function, Expr values) implements Expr {
    }

    /** What {@link Aggregate} computes. */
    enum AggregateFunction {
        MEAN, CV
    }

    /** {@code ref-to}: a {@link Value.Ref} to an element, resolved by the engine. */
    record RefTo(String element, Expr name) implements Expr {
    }

    /** {@code fn}: a named function. */
    record Fn(String name, List<Expr> args) implements Expr {

        public Fn {
            args = List.copyOf(args);
        }
    }
}
