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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What an expression sees: the instance, the mapping's parameters, the notes, and the variables bound by the
 * enclosing {@code each}, {@code map}, {@code find} and {@code filter} forms, each with its 1-based position.
 */
public final class Scope {

    /** A bound variable. */
    record Binding(Value value, int position) {
    }

    /** State shared by a root scope and all scopes derived from it. */
    private static final class Root {

        final JsonNode instance;
        final Value.Obj parameters;
        final Notes notes;
        final Map<String, Value> instanceValues = new HashMap<>();
        final Map<String, Value> letValues = new HashMap<>();
        final Set<String> evaluating = new HashSet<>();

        Root(JsonNode instance, Map<String, String> parameters, Notes notes) {
            this.instance = Objects.requireNonNull(instance);
            LinkedHashMap<String, Value> p = new LinkedHashMap<>();
            parameters.forEach((k, v) -> p.put(k, v == null ? Value.Null.INSTANCE : new Value.Text(v)));
            this.parameters = new Value.Obj(p);
            this.notes = Objects.requireNonNull(notes);
        }
    }

    private final Root root;
    private final Scope parent;
    private final String name;
    private final Binding binding;

    private Scope(Root root, Scope parent, String name, Binding binding) {
        this.root = root;
        this.parent = parent;
        this.name = name;
        this.binding = binding;
    }

    /** The scope of a whole mapping run. */
    public static Scope root(JsonNode instance, Map<String, String> parameters, Notes notes) {
        return new Scope(new Root(instance, parameters, notes), null, null, null);
    }

    /** This scope with {@code name} bound to {@code v}, at 1-based {@code position} among the items being visited. */
    public Scope with(String name, Value v, int position) {
        return new Scope(root, this, Objects.requireNonNull(name), new Binding(Objects.requireNonNull(v), position));
    }

    /** The notes of this run. */
    public Notes notes() {
        return root.notes;
    }

    /** The innermost binding of {@code variable}, or null. */
    Binding binding(String variable) {
        for (Scope s = this; s != null; s = s.parent) {
            if (variable.equals(s.name)) {
                return s.binding;
            }
        }
        return null;
    }

    /** The mapping's parameters as an object of texts. */
    Value.Obj parameters() {
        return root.parameters;
    }

    /** The top-level instance member {@code key}, or Null. */
    Value instanceMember(String key) {
        return root.instanceValues.computeIfAbsent(key, k -> Value.of(root.instance.get(k)));
    }

    /** The scope without any variables. */
    Scope top() {
        Scope s = this;
        while (s.parent != null) {
            s = s.parent;
        }
        return s;
    }

    Map<String, Value> letValues() {
        return root.letValues;
    }

    Set<String> evaluating() {
        return root.evaluating;
    }
}
