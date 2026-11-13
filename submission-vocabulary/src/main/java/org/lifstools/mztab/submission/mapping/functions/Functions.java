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

import java.util.List;
import org.lifstools.mztab.submission.mapping.Evaluator;
import org.lifstools.mztab.submission.mapping.Notes;
import org.lifstools.mztab.submission.mapping.Value;

/**
 * The named functions of the {@code fn} form, as {@code schema/mapping-language.md} defines them. A wrong number of
 * arguments, or an argument of the wrong kind, throws {@link IllegalArgumentException}.
 */
public final class Functions implements Evaluator.FunctionRegistry {

    @Override
    public Value call(String name, List<Value> args, Notes notes) {
        return switch (name) {
            case "goslin-parse" -> {
                arity(name, args, 1);
                yield GoslinParse.parse(args.get(0));
            }
            case "strip-name-notes" -> {
                arity(name, args, 1);
                yield StripNameNotes.strip(args.get(0));
            }
            case "factor-combinations" -> {
                arity(name, args, 3);
                yield FactorCombinations.of(args.get(0), args.get(1), args.get(2));
            }
            case "formula-check" -> {
                arity(name, args, 4);
                yield FormulaCheck.check(args.get(0), args.get(1), args.get(2), args.get(3), notes);
            }
            default -> throw new IllegalArgumentException("No implementation of the function " + name);
        };
    }

    /** False in Python: None, an empty text, list or object, and a zero. */
    static boolean falsy(Value v) {
        return Value.isEmpty(v) || v instanceof Value.Int i && i.value() == 0 || v instanceof Value.Dbl d && d.value() == 0.0;
    }

    private static void arity(String name, List<Value> args, int n) {
        if (args.size() != n) {
            throw new IllegalArgumentException(name + " takes " + n + " arguments, not " + args.size());
        }
    }
}
