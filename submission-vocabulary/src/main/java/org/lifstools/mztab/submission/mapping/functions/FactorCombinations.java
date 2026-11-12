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
package org.lifstools.mztab.submission.mapping.functions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.lifstools.mztab.submission.mapping.MappingRequestException;
import org.lifstools.mztab.submission.mapping.Value;

/**
 * {@code factor-combinations}: the study groups of a dataset as the v3 converter forms them
 * ({@code Converter.study_variables}, {@code conv:333-340}, and {@code factor_value}, {@code conv:169-173}).
 */
final class FactorCombinations {

    /** The factors read from the sample record itself rather than from its {@code factors}. */
    private static final Set<String> SAMPLE_FIELDS = Set.of("sex", "age", "disease");

    private static final Value NOT_SPECIFIED = new Value.Obj(new LinkedHashMap<>(Map.of("text", new Value.Text("not specified"))));

    private FactorCombinations() {
    }

    /**
     * One {@code Obj{key, members}} per combination of the primary factors' values, in order of first appearance.
     * {@code key} lists {@code Obj{factor, value}} per primary factor, and {@code members} the 1-based positions in
     * {@code names} of the samples with that combination.
     *
     * @param names the dataset's sample names, in assay order
     * @param samples the sample records: a list, each found by its {@code sample_name} (the last of a name counts,
     *     as {@code conv:156}), or an object of them keyed by name
     * @param factors the factor records; those whose {@code primary.text} is {@code yes} are the primary factors
     * @throws IllegalArgumentException when a name has no sample, or an argument is of the wrong kind
     */
    static Value of(Value names, Value samples, Value factors) {
        Map<String, Value> byName = byName(samples);
        List<String> primary = new ArrayList<>();
        for (Value f : items(factors, "factors")) {
            if (field(field(f, "primary"), "text").equals(new Value.Text("yes"))) {
                primary.add(text(field(f, "factor_name"), "a factor_name"));
            }
        }
        Map<Value, List<Value>> groups = new LinkedHashMap<>();
        int position = 0;
        for (Value n : items(names, "names")) {
            position++;
            String name = text(n, "a sample name");
            Value sample = byName.get(name);
            if (sample == null) {
                throw new MappingRequestException("factor-combinations: no sample is called '" + name + "'");
            }
            List<Value> key = new ArrayList<>(primary.size());
            for (String factor : primary) {
                Value v = value(sample, factor);
                LinkedHashMap<String, Value> pair = new LinkedHashMap<>();
                pair.put("factor", new Value.Text(factor));
                pair.put("value", Functions.falsy(v) ? NOT_SPECIFIED : v);
                key.add(new Value.Obj(pair));
            }
            groups.computeIfAbsent(new Value.ListV(key), k -> new ArrayList<>()).add(new Value.Int(position));
        }
        List<Value> out = new ArrayList<>(groups.size());
        groups.forEach((key, members) -> {
            LinkedHashMap<String, Value> group = new LinkedHashMap<>();
            group.put("key", key);
            group.put("members", new Value.ListV(members));
            out.add(new Value.Obj(group));
        });
        return new Value.ListV(out);
    }

    /** {@code factor_value}: sex, age and disease from the sample record, other factors from its {@code factors}, ignoring case. */
    private static Value value(Value sample, String factor) {
        String key = factor.toLowerCase(Locale.ROOT);
        if (SAMPLE_FIELDS.contains(key)) {
            return field(sample, key);
        }
        if (field(sample, "factors") instanceof Value.Obj own) {
            for (Map.Entry<String, Value> e : own.value().entrySet()) {
                if (e.getKey().toLowerCase(Locale.ROOT).equals(key)) {
                    return e.getValue();
                }
            }
        }
        return Value.Null.INSTANCE;
    }

    private static Map<String, Value> byName(Value samples) {
        if (samples instanceof Value.Obj o) {
            return o.value();
        }
        Map<String, Value> byName = new LinkedHashMap<>();
        for (Value s : items(samples, "samples")) {
            byName.put(text(field(s, "sample_name"), "a sample_name"), s);
        }
        return byName;
    }

    private static List<Value> items(Value v, String what) {
        if (v == Value.Null.INSTANCE) {
            return List.of();
        }
        if (v instanceof Value.ListV l) {
            return l.value();
        }
        throw new MappingRequestException("factor-combinations needs a list of " + what + ", not " + v);
    }

    private static Value field(Value v, String key) {
        return v instanceof Value.Obj o ? o.get(key) : Value.Null.INSTANCE;
    }

    private static String text(Value v, String what) {
        if (v instanceof Value.Text t) {
            return t.value();
        }
        throw new MappingRequestException("factor-combinations needs " + what + " as a text, not " + v);
    }
}
