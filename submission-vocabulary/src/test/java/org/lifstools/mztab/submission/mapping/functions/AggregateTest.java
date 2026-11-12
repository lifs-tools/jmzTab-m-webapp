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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.submission.Vocabulary;
import org.lifstools.mztab.submission.mapping.Evaluator;
import org.lifstools.mztab.submission.mapping.Expr;
import org.lifstools.mztab.submission.mapping.Notes;
import org.lifstools.mztab.submission.mapping.Numbers;
import org.lifstools.mztab.submission.mapping.Scope;
import org.lifstools.mztab.submission.mapping.Value;

/** The study-variable abundances of the v3 example, through {@code aggregate} and {@code factor-combinations}. */
class AggregateTest {

    private static final Value NULL = Value.Null.INSTANCE;
    private static final JsonNode INSTANCE = V3Example.instance();

    private final Functions functions = new Functions();
    private final Evaluator evaluator = new Evaluator(Map.of(), Vocabulary.load(), functions);
    private final Scope scope = Scope.root(INSTANCE, Map.of(), new Notes());

    private Value aggregate(Expr.AggregateFunction function, Value values) {
        return evaluator.eval(new Expr.Aggregate(function, new Expr.Literal(values)), scope);
    }

    private Value mean(Value... values) {
        return aggregate(Expr.AggregateFunction.MEAN, new Value.ListV(List.of(values)));
    }

    private Value cv(Value... values) {
        return aggregate(Expr.AggregateFunction.CV, new Value.ListV(List.of(values)));
    }

    private static String cell(Value v) {
        return v == NULL ? "null" : Numbers.python(v);
    }

    private JsonNode quantities(int dataset) {
        return INSTANCE.get("datasets").get(dataset).get("quantities");
    }

    /** The study groups of a dataset, from {@code factor-combinations}. */
    private List<Value> groups(JsonNode quantities) {
        Value groups = functions.call("factor-combinations", List.of(Value.of(quantities.get("samples")),
                Value.of(INSTANCE.get("samples")), Value.of(INSTANCE.get("factors"))), new Notes());
        return ((Value.ListV) groups).value();
    }

    /** The values of lipid {@code j} for the members of {@code group}, in member order. */
    private static Value values(JsonNode quantities, int j, Value group) {
        List<Value> out = new ArrayList<>();
        for (Value m : ((Value.ListV) ((Value.Obj) group).get("members")).value()) {
            out.add(Value.of(quantities.get("values").get((int) ((Value.Int) m).value() - 1).get(j)));
        }
        return new Value.ListV(out);
    }

    @Test
    void theMeanOfTheFirstStudyGroupOfSml3() {
        JsonNode q = quantities(0);
        Value values = values(q, 2, groups(q).get(0));
        assertThat(values).isEqualTo(new Value.ListV(List.of(new Value.Dbl(138.207), new Value.Dbl(174.789),
                new Value.Dbl(173.215), new Value.Dbl(131.513))));
        assertThat(Numbers.python(aggregate(Expr.AggregateFunction.MEAN, values))).isEqualTo("154.43099999999998");
    }

    @Test
    void edgeCases() {
        assertThat(Numbers.python(mean(new Value.Int(2), new Value.Int(2)))).isEqualTo("2.0");
        assertThat(cv(new Value.Dbl(5.0))).isEqualTo(NULL);
        assertThat(mean(NULL, NULL)).isEqualTo(NULL);
        assertThat(cv(NULL, NULL)).isEqualTo(NULL);
    }

    @Test
    void givesEveryStudyVariableAbundanceOfTheV3Example() {
        String[] files = {"LCS-99999-01.mzTab", "LCS-99999-02.mzTab"};
        for (int d = 0; d < files.length; d++) {
            JsonNode q = quantities(d);
            List<Value> groups = groups(q);
            List<Map<String, String>> rows = V3Example.smallMolecules(files[d]);
            assertThat(rows).hasSize(q.get("lipids").size());
            for (int j = 0; j < rows.size(); j++) {
                for (int g = 0; g < groups.size(); g++) {
                    Value values = values(q, j, groups.get(g));
                    String where = files[d] + " SML " + (j + 1) + " study_variable " + (g + 1);
                    assertThat(cell(aggregate(Expr.AggregateFunction.MEAN, values))).as(where)
                            .isEqualTo(rows.get(j).get("abundance_study_variable[" + (g + 1) + "]"));
                    assertThat(cell(aggregate(Expr.AggregateFunction.CV, values))).as(where)
                            .isEqualTo(rows.get(j).get("abundance_variation_study_variable[" + (g + 1) + "]"));
                }
            }
        }
    }
}
