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

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.submission.mapping.Notes;
import org.lifstools.mztab.submission.mapping.Value;

class FormulaCheckTest {

    private static final Value NULL = Value.Null.INSTANCE;
    private static final Value SHEET = new Value.Text("Q_D1");

    private final Functions functions = new Functions();
    private final Notes notes = new Notes();

    private Value parsed(String name) {
        return functions.call("goslin-parse", List.of(new Value.Text(name)), notes);
    }

    private Value check(String name, Value stated) {
        return functions.call("formula-check", List.of(SHEET, new Value.Text(name), parsed(name), stated), notes);
    }

    private Value check(String name, String stated) {
        return check(name, new Value.Text(stated));
    }

    private static Value result(Value formula, Value mass) {
        LinkedHashMap<String, Value> m = new LinkedHashMap<>();
        m.put("formula", formula);
        m.put("mass", mass);
        return new Value.Obj(m);
    }

    private static Value result(String formula, Value mass) {
        return result(new Value.Text(formula), mass);
    }

    private Value mass(String name) {
        return ((Value.Obj) parsed(name)).get("mass");
    }

    @Test
    void withoutAStatedFormulaGivesGoslinsFormulaAndMass() {
        assertThat(check("PC 34:1", NULL)).isEqualTo(result("C42H82NO8P", mass("PC 34:1")));
        assertThat(check("PC 34:1", "")).isEqualTo(result("C42H82NO8P", mass("PC 34:1")));
        assertThat(check("PC 15:0-18:1(d7)", NULL)).isEqualTo(result(NULL, mass("PC 15:0-18:1(d7)")));
        assertThat(check("not a lipid", NULL)).isEqualTo(result(NULL, NULL));
        assertThat(notes.all()).isEmpty();
    }

    @Test
    void aMatchingStatedFormulaIsWrittenWithTheMass() {
        assertThat(check("PC 34:1", "C42H82NO8P")).isEqualTo(result("C42H82NO8P", new Value.Dbl(759.57780591)));
        // the element counts are compared, not the texts
        assertThat(check("PC 34:1", " H82C42NO8P1 ")).isEqualTo(result("H82C42NO8P1", mass("PC 34:1")));
        assertThat(notes.all()).isEmpty();
    }

    @Test
    void aStatedFormulaThatDoesNotMatchIsWrittenWithoutAMass() {
        assertThat(check("PC 34:1", "C40H80NO8P")).isEqualTo(result("C40H80NO8P", NULL));
        assertThat(notes.all()).containsExactly("Q_D1: sum formula C40H80NO8P of 'PC 34:1' does not match the name "
                + "(C42H82NO8P, Goslin); the stated formula is written, without a mass");
    }

    @Test
    void aStatedFormulaThatIsNotPlainIsNotWritten() {
        assertThat(check("PC 34:1", "C42 H82NO8P")).isEqualTo(result("C42H82NO8P", mass("PC 34:1")));
        assertThat(check("not a lipid", "C42H82NO8P+")).isEqualTo(result(NULL, NULL));
        assertThat(notes.all()).containsExactly(
                "Q_D1: sum formula 'C42 H82NO8P' of 'PC 34:1' is not a plain formula (e.g. C42H82NO8P); not written",
                "Q_D1: sum formula 'C42H82NO8P+' of 'not a lipid' is not a plain formula (e.g. C42H82NO8P); not written");
    }

    @Test
    void aStatedFormulaIsNotCheckedWhenGoslinGivesNoPlainFormula() {
        assertThat(check("PC 15:0-18:1(d7)", "C41H75D7NO8P"))
                .isEqualTo(result("C41H75D7NO8P", mass("PC 15:0-18:1(d7)")));
        assertThat(notes.all()).containsExactly(
                "Q_D1: sum formula C41H75D7NO8P of 'PC 15:0-18:1(d7)' not checked (Goslin gives no plain formula)");
    }

    @Test
    void aStatedFormulaOfAnUnparsedNameIsWrittenWithoutAMass() {
        assertThat(check("not a lipid", "C42H82NO8P")).isEqualTo(result("C42H82NO8P", NULL));
        assertThat(notes.all()).isEmpty();
    }

    @Test
    void aNumberIsStatedAsItsPythonText() {
        assertThat(check("not a lipid", new Value.Int(12))).isEqualTo(result(NULL, NULL));
        assertThat(notes.all()).containsExactly(
                "Q_D1: sum formula '12' of 'not a lipid' is not a plain formula (e.g. C42H82NO8P); not written");
    }
}
