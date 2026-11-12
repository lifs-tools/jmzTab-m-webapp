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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lifstools.mztab.submission.mapping.Notes;
import org.lifstools.mztab.submission.mapping.Numbers;
import org.lifstools.mztab.submission.mapping.Value;

class GoslinFunctionTest {

    private final Notes notes = new Notes();

    private Value goslin(Value name) {
        return new Functions().call("goslin-parse", List.of(name), notes);
    }

    private Value goslin(String name) {
        return goslin(new Value.Text(name));
    }

    private static Value obj(String normalised, String formula, double mass) {
        LinkedHashMap<String, Value> m = new LinkedHashMap<>();
        m.put("normalised", new Value.Text(normalised));
        m.put("formula", formula == null ? Value.Null.INSTANCE : new Value.Text(formula));
        m.put("mass", new Value.Dbl(mass));
        return new Value.Obj(m);
    }

    @Test
    void parsesNamesAsTheConverterDoes() {
        assertThat(goslin("PC 34:1")).isEqualTo(obj("PC 34:1", "C42H82NO8P", 759.57780591));
        assertThat(goslin("SM 34:1;O2")).isEqualTo(obj("SM 34:1;O2", "C39H79N2O6P", 702.567575545));
        assertThat(goslin("PC 15:0-18:1(d7)")).isEqualTo(obj("PC 15:0_18:1[M[2]H7]", null, 752.606093048));
        assertThat(notes.all()).isEmpty();
    }

    @Test
    void anUnparsableNameIsNull() {
        assertThat(goslin("not a lipid")).isEqualTo(Value.Null.INSTANCE);
        assertThat(goslin(Value.Null.INSTANCE)).isEqualTo(Value.Null.INSTANCE);
        assertThat(goslin("")).isEqualTo(Value.Null.INSTANCE);
    }

    @Test
    void aNameMustBeAText() {
        assertThatThrownBy(() -> goslin(new Value.Int(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Functions().call("goslin-parse", List.of(), notes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LCS-99999-01.mzTab", "LCS-99999-02.mzTab"})
    void givesTheConvertersNamesAndMassesForTheV3Example(String file) {
        List<Map<String, String>> rows = V3Example.smallMolecules(file);
        assertThat(rows).isNotEmpty();
        for (Map<String, String> row : rows) {
            Value.Obj parsed = (Value.Obj) goslin(row.get("opt_global_original_name"));
            assertThat(((Value.Text) parsed.get("normalised")).value()).isEqualTo(row.get("chemical_name"));
            assertThat(Numbers.python(parsed.get("mass")))
                    .as("mass of %s", row.get("opt_global_original_name"))
                    .isEqualTo(row.get("theoretical_neutral_mass"));
        }
    }
}
