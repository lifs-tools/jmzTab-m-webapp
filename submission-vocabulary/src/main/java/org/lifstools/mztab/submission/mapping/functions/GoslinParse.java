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

import java.util.Arrays;
import java.util.LinkedHashMap;
import org.lifstools.jgoslin.domain.Element;
import org.lifstools.jgoslin.domain.ElementTable;
import org.lifstools.jgoslin.domain.Elements;
import org.lifstools.jgoslin.domain.LipidAdduct;
import org.lifstools.jgoslin.parser.LipidParser;
import org.lifstools.mztab.submission.mapping.PyMath;
import org.lifstools.mztab.submission.mapping.Value;

/** {@code goslin-parse}: a lipid name read by Goslin, as the v3 converter reads it with pygoslin. */
final class GoslinParse {

    /** The parser is costly to build and keeps state while it parses, so there is one, used by one caller at a time. */
    private static final class Parser {

        static final LipidParser INSTANCE = new LipidParser();
    }

    private GoslinParse() {
    }

    /**
     * {@code Obj{normalised, formula, mass}} for a name Goslin reads, else Null. Null gives Null.
     *
     * @throws IllegalArgumentException when the name is not a text
     */
    static Value parse(Value name) {
        if (name == Value.Null.INSTANCE) {
            return name;
        }
        if (!(name instanceof Value.Text text)) {
            throw new IllegalArgumentException("goslin-parse needs a text, not " + name);
        }
        LipidAdduct lipid;
        try {
            synchronized (Parser.INSTANCE) {
                lipid = Parser.INSTANCE.parse(text.value());
            }
        } catch (RuntimeException e) { // conv:163-167: any failure means the name does not parse
            return Value.Null.INSTANCE;
        }
        String formula = lipid.getSumFormula();
        LinkedHashMap<String, Value> m = new LinkedHashMap<>();
        m.put("normalised", new Value.Text(lipid.getLipidString()));
        m.put("formula", FormulaCheck.isPlain(formula) ? new Value.Text(formula) : Value.Null.INSTANCE);
        m.put("mass", new Value.Dbl(mass(lipid)));
        return new Value.Obj(m);
    }

    /**
     * pygoslin's {@code LipidAdduct.get_mass} under CPython 3.12 or later: the element masses times their counts,
     * added in {@link Element} order with the builtin {@code sum} (Neumaier's summation), then corrected for the
     * adduct's charge. jgoslin's {@code getMass()} adds plainly and can differ in the last bit.
     */
    static double mass(LipidAdduct lipid) {
        ElementTable elements = lipid.getElements();
        double[] terms = new double[Element.values().length];
        int n = 0;
        for (Element e : Element.values()) {
            Double m = Elements.ELEMENT_MASSES.get(e);
            if (m != null) { // jgoslin's D has no mass of its own: deuterium is counted as H2
                terms[n++] = m * elements.getOrDefault(e, 0);
            }
        }
        double mass = PyMath.neumaierSum(Arrays.copyOf(terms, n));
        int charge = lipid.getAdduct() == null ? 0 : lipid.getAdduct().getCharge();
        if (charge != 0) {
            mass = (mass - charge * Elements.ELECTRON_REST_MASS) / Math.abs(charge);
        }
        return mass;
    }
}
