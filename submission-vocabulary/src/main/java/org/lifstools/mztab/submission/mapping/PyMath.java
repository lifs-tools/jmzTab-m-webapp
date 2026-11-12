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
import java.math.BigInteger;

/** Python's float arithmetic where the v3 converter relies on it (CPython 3.12 and later). */
public final class PyMath {

    /** CPython's {@code _sqrt_bit_width}: {@code 2 * sys.float_info.mant_dig + 3}. */
    private static final int SQRT_BIT_WIDTH = 2 * 53 + 3;

    private PyMath() {
    }

    /** {@code math.fsum}: the correctly rounded sum, with Shewchuk's partials as CPython computes it. */
    public static double fsum(double[] values) {
        double[] p = new double[Math.max(8, values.length + 1)];
        int n = 0;
        double specialSum = 0.0;
        double infSum = 0.0;
        for (double value : values) {
            double x = value;
            int i = 0;
            for (int j = 0; j < n; j++) {
                double y = p[j];
                if (Math.abs(x) < Math.abs(y)) {
                    double t = x;
                    x = y;
                    y = t;
                }
                double hi = x + y;
                double yr = hi - x;
                double lo = y - yr;
                if (lo != 0.0) {
                    p[i++] = lo;
                }
                x = hi;
            }
            n = i;
            if (x != 0.0) {
                if (!Double.isFinite(x)) {
                    if (Double.isFinite(value)) {
                        throw new ArithmeticException("intermediate overflow in fsum");
                    }
                    if (Double.isInfinite(value)) {
                        infSum += value;
                    }
                    specialSum += value;
                    n = 0;
                } else {
                    if (n == p.length) {
                        p = java.util.Arrays.copyOf(p, 2 * n);
                    }
                    p[n++] = x;
                }
            }
        }
        if (specialSum != 0.0) {
            if (Double.isNaN(infSum)) {
                throw new ArithmeticException("-inf + inf in fsum");
            }
            return specialSum;
        }
        double hi = 0.0;
        if (n > 0) {
            double lo = 0.0;
            hi = p[--n];
            // sum the partials from the top, and stop when the sum becomes inexact
            while (n > 0) {
                double x = hi;
                double y = p[--n];
                hi = x + y;
                double yr = hi - x;
                lo = y - yr;
                if (lo != 0.0) {
                    break;
                }
            }
            // make half-even rounding work across several partials
            if (n > 0 && ((lo < 0.0 && p[n - 1] < 0.0) || (lo > 0.0 && p[n - 1] > 0.0))) {
                double y = lo * 2.0;
                double x = hi + y;
                double yr = x - hi;
                if (y == yr) {
                    hi = x;
                }
            }
        }
        return hi;
    }

    /** {@code statistics.fmean}: {@code fsum(values) / n}. */
    public static double fmean(double[] values) {
        if (values.length == 0) {
            throw new MappingRequestException("fmean requires at least one data point");
        }
        return fsum(values) / values.length;
    }

    /**
     * {@code statistics.stdev} (CPython 3.12 and later): the square root, correctly rounded, of the exact sample
     * variance of the values' exact binary values.
     */
    public static double stdev(double[] values) {
        int count = values.length;
        if (count < 2) {
            throw new MappingRequestException("stdev requires at least two data points");
        }
        BigDecimal sx = BigDecimal.ZERO;
        BigDecimal sxx = BigDecimal.ZERO;
        for (double v : values) {
            if (!Double.isFinite(v)) {
                return Double.NaN;
            }
            BigDecimal x = new BigDecimal(v); // exact
            sx = sx.add(x);
            sxx = sxx.add(x.multiply(x));
        }
        // variance = (count * sxx - sx^2) / (count * (count - 1)), exactly
        BigDecimal ss = sxx.multiply(BigDecimal.valueOf(count)).subtract(sx.multiply(sx));
        BigInteger num = ss.unscaledValue();
        BigInteger den = BigInteger.valueOf((long) count * (count - 1));
        if (ss.scale() > 0) {
            den = den.multiply(BigInteger.TEN.pow(ss.scale()));
        } else {
            num = num.multiply(BigInteger.TEN.pow(-ss.scale()));
        }
        return sqrtOfFraction(num, den);
    }

    /** CPython {@code statistics._float_sqrt_of_frac}: the square root of n/m as a float, correctly rounded. */
    private static double sqrtOfFraction(BigInteger n, BigInteger m) {
        if (n.signum() == 0) {
            return 0.0;
        }
        int q = Math.floorDiv(n.bitLength() - m.bitLength() - SQRT_BIT_WIDTH, 2);
        if (q >= 0) {
            BigInteger numerator = sqrtRoundToOdd(n, m.shiftLeft(2 * q)).shiftLeft(q);
            return numerator.doubleValue();
        }
        BigInteger numerator = sqrtRoundToOdd(n.shiftLeft(-2 * q), m);
        // numerator / 2^-q: numerator has at least 55 significant bits, so the conversion rounds once, and the
        // scaling is exact unless the result is subnormal
        return Math.scalb(numerator.doubleValue(), q);
    }

    /** CPython {@code statistics._integer_sqrt_of_frac_rto}: the square root of n/m, rounded to odd. */
    private static BigInteger sqrtRoundToOdd(BigInteger n, BigInteger m) {
        BigInteger a = n.divide(m).sqrt();
        return a.multiply(a).multiply(m).equals(n) ? a : a.or(BigInteger.ONE);
    }

    /**
     * CPython 3.12+ builtin {@code sum} over floats: Neumaier's compensated summation, the compensation added at the
     * end when it is finite and not zero.
     */
    public static double neumaierSum(double[] values) {
        double result = 0.0;
        double c = 0.0;
        for (double x : values) {
            double t = result + x;
            if (Math.abs(result) >= Math.abs(x)) {
                c += (result - t) + x;
            } else {
                c += (x - t) + result;
            }
            result = t;
        }
        if (c != 0.0 && Double.isFinite(c)) {
            result += c;
        }
        return result;
    }
}
