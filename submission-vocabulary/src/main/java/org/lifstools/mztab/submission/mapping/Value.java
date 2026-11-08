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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.lifstools.mztab.submission.Vocabulary.Param;

/**
 * A value of the mapping language. Integers are held as {@code long} and floats as {@code double}, as the JSON
 * instance holds them; tests yield {@link #TRUE} or {@link Null}.
 */
public sealed interface Value {

    /** What a test (eq, not-empty) gives when it holds; when it does not, it gives {@link Null}. */
    Value TRUE = new Text("true");

    /** No value: JSON null, a missing field, or a failed lookup. */
    enum Null implements Value {
        INSTANCE;

        @Override
        public String toString() {
            return "Null";
        }
    }

    /** A text. */
    record Text(String value) implements Value {

        public Text {
            Objects.requireNonNull(value);
        }
    }

    /** An integer, from a JSON integer node. */
    record Int(long value) implements Value {
    }

    /** A float, from a JSON float node or arithmetic. */
    record Dbl(double value) implements Value {
    }

    /** An object; the map is not to be changed after construction. */
    record Obj(LinkedHashMap<String, Value> value) implements Value {

        public Obj {
            Objects.requireNonNull(value);
        }

        /** The member {@code key}, or Null. */
        public Value get(String key) {
            Value v = value.get(key);
            return v == null ? Null.INSTANCE : v;
        }
    }

    /** A list. */
    record ListV(List<Value> value) implements Value {

        public ListV {
            value = List.copyOf(value);
        }
    }

    /** A parameter, rendered with {@link Render#param(Param)}. */
    record ParamV(Param value) implements Value {

        public ParamV {
            Objects.requireNonNull(value);
        }
    }

    /** A symbolic reference to the element of kind {@code element} (e.g. {@code sample}) called {@code name}; the engine resolves it. */
    record Ref(String element, Value name) implements Value {

        public Ref {
            Objects.requireNonNull(element);
            Objects.requireNonNull(name);
        }
    }

    /**
     * The value of a JSON (or YAML) node: objects, arrays, texts, integers (as long), floats (as double) and null. A
     * boolean becomes the text {@code true} or {@code false}.
     */
    static Value of(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Null.INSTANCE;
        }
        if (node.isObject()) {
            LinkedHashMap<String, Value> m = new LinkedHashMap<>();
            node.properties().forEach(e -> m.put(e.getKey(), of(e.getValue())));
            return new Obj(m);
        }
        if (node.isArray()) {
            List<Value> items = new ArrayList<>(node.size());
            node.forEach(n -> items.add(of(n)));
            return new ListV(items);
        }
        if (node.isIntegralNumber()) {
            if (!node.canConvertToLong()) {
                throw new IllegalArgumentException("Integer out of range: " + node.asText());
            }
            return new Int(node.longValue());
        }
        if (node.isNumber()) {
            return new Dbl(node.doubleValue());
        }
        if (node.isBoolean()) {
            return new Text(node.booleanValue() ? "true" : "false");
        }
        if (node.isTextual()) {
            return new Text(node.textValue());
        }
        throw new IllegalArgumentException("Not a value: " + node.getNodeType());
    }

    /** True for Null, the text "", an empty list and an empty object. Numbers, 0 included, are not empty. */
    static boolean isEmpty(Value v) {
        return switch (v) {
            case Null n -> true;
            case Text t -> t.value().isEmpty();
            case ListV l -> l.value().isEmpty();
            case Obj o -> o.value().isEmpty();
            default -> false;
        };
    }
}
