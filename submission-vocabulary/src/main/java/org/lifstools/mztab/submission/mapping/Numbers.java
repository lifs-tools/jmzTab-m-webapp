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

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Numbers as Python's {@code str()} writes them. */
public final class Numbers {

    private Numbers() {
    }

    /**
     * Python {@code str(v)}: an integer as its digits, a float as {@link #python(double)}, a text as itself.
     *
     * @throws IllegalArgumentException for any other value
     */
    public static String python(Value v) {
        return switch (v) {
            case Value.Int i -> Long.toString(i.value());
            case Value.Dbl d -> python(d.value());
            case Value.Text t -> t.value();
            default -> throw new IllegalArgumentException("Not a number or a text: " + v);
        };
    }

    /**
     * Python {@code repr(x)} of a float: the shortest decimal that reads back as {@code x} (the closest one when
     * there are several), positional with a trailing {@code .0} for integral values, scientific ({@code 1e-05},
     * {@code 1.5e+16}) when the decimal exponent is below -4 or at least 16.
     */
    public static String python(double x) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        if (x == 0) {
            return (Double.doubleToRawLongBits(x) < 0) ? "-0.0" : "0.0";
        }
        BigDecimal shortest = shortest(Math.abs(x));
        String digits = shortest.unscaledValue().toString();
        int exponent = digits.length() - 1 - shortest.scale(); // of the first digit
        String sign = x < 0 ? "-" : "";
        if (exponent < -4 || exponent >= 16) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            String e = Integer.toString(Math.abs(exponent));
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + (e.length() < 2 ? "0" + e : e);
        }
        if (exponent < 0) {
            return sign + "0." + "0".repeat(-exponent - 1) + digits;
        }
        if (exponent + 1 >= digits.length()) {
            return sign + digits + "0".repeat(exponent + 1 - digits.length()) + ".0";
        }
        return sign + digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1);
    }

    /**
     * The shortest decimal (trailing zeros stripped) that reads back as {@code x > 0}. Java's {@code Double.toString}
     * gives the shortest of at least two digits; where a one-digit decimal also reads back, Python takes that one.
     */
    private static BigDecimal shortest(double x) {
        BigDecimal java = new BigDecimal(Double.toString(x)).stripTrailingZeros();
        if (java.precision() != 2) {
            return java;
        }
        BigDecimal exact = new BigDecimal(x);
        BigDecimal best = null;
        for (RoundingMode mode : new RoundingMode[]{RoundingMode.FLOOR, RoundingMode.CEILING}) {
            BigDecimal candidate = exact.round(new java.math.MathContext(1, mode));
            if (candidate.signum() > 0 && candidate.doubleValue() == x) {
                if (best == null || candidate.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0) {
                    best = candidate;
                }
            }
        }
        return best == null ? java : best.stripTrailingZeros();
    }
}
