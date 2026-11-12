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

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.lifstools.mztab.submission.mapping.Notes;
import org.lifstools.mztab.submission.mapping.Numbers;
import org.lifstools.mztab.submission.mapping.Render;
import org.lifstools.mztab.submission.mapping.Value;

/** {@code formula-check}: a stated sum formula checked against Goslin's, as {@code Converter.formula} ({@code conv:291-309}). */
final class FormulaCheck {

    /** {@code conv:102}; Python's {@code \d} is any Unicode decimal digit. */
    private static final Pattern FORMULA = Pattern.compile("(?:[A-Z][a-z]?\\p{Nd}*)+");

    /** {@code conv:107}. */
    private static final Pattern ELEMENT = Pattern.compile("([A-Z][a-z]?)(\\p{Nd}*)");

    private FormulaCheck() {
    }

    /**
     * {@code Obj{formula, mass}}: without a stated formula, Goslin's formula and mass; with one, the stated formula,
     * and the mass only when the formula is not known to contradict the name.
     *
     * @param sheet the sheet named in the notes
     * @param name the lipid name as written
     * @param parsed the name's {@code goslin-parse}, or Null
     * @param stated the stated sum formula, or Null
     */
    static Value check(Value sheet, Value name, Value parsed, Value stated, Notes notes) {
        Value derived = member(parsed, "formula");
        Value mass = member(parsed, "mass");
        if (Functions.falsy(stated)) {
            return result(derived, mass);
        }
        String formula = Render.text(Numbers.python(stated));
        String where = Numbers.python(sheet) + ": sum formula ";
        if (!isPlain(formula)) {
            notes.add(where + "'" + formula + "' of '" + Numbers.python(name)
                    + "' is not a plain formula (e.g. C42H82NO8P); not written");
            return result(derived, mass);
        }
        if (derived instanceof Value.Text d && !elements(formula).equals(elements(d.value()))) {
            notes.add(where + formula + " of '" + Numbers.python(name) + "' does not match the name (" + d.value()
                    + ", Goslin); the stated formula is written, without a mass");
            return result(new Value.Text(formula), Value.Null.INSTANCE);
        }
        if (parsed != Value.Null.INSTANCE && derived == Value.Null.INSTANCE) {
            notes.add(where + formula + " of '" + Numbers.python(name) + "' not checked (Goslin gives no plain formula)");
        }
        return result(new Value.Text(formula), mass);
    }

    /** A plain sum formula, e.g. {@code C42H82NO8P} ({@code conv:102}); Goslin's {@code C41H73NO8PH'7} is not one. */
    static boolean isPlain(String formula) {
        return FORMULA.matcher(formula).matches();
    }

    /** {@code conv:105-109}: the count of each element; a missing count is 1. */
    private static Map<String, BigInteger> elements(String formula) {
        Map<String, BigInteger> counts = new HashMap<>();
        Matcher m = ELEMENT.matcher(formula);
        while (m.find()) {
            counts.merge(m.group(1), count(m.group(2)), BigInteger::add);
        }
        return counts;
    }

    /** Python {@code int(n or 1)}: the digits may be any Unicode decimal digits. */
    private static BigInteger count(String digits) {
        if (digits.isEmpty()) {
            return BigInteger.ONE;
        }
        BigInteger n = BigInteger.ZERO;
        for (int cp : digits.codePoints().toArray()) {
            n = n.multiply(BigInteger.TEN).add(BigInteger.valueOf(Character.digit(cp, 10)));
        }
        return n;
    }

    private static Value member(Value v, String key) {
        return v instanceof Value.Obj o ? o.get(key) : Value.Null.INSTANCE;
    }

    private static Value result(Value formula, Value mass) {
        LinkedHashMap<String, Value> m = new LinkedHashMap<>();
        m.put("formula", formula);
        m.put("mass", mass);
        return new Value.Obj(m);
    }
}
