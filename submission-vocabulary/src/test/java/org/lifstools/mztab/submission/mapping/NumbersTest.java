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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NumbersTest {

    private static String py(Value v) {
        return Numbers.python(v);
    }

    @Test
    void rendersAsPythonStr() {
        assertThat(py(new Value.Dbl(29.7))).isEqualTo("29.7");
        assertThat(py(new Value.Int(45))).isEqualTo("45");
        assertThat(py(new Value.Dbl(45.0))).isEqualTo("45.0");
        assertThat(py(new Value.Dbl(1e-5))).isEqualTo("1e-05");
        assertThat(py(new Value.Dbl(1.5e-5))).isEqualTo("1.5e-05");
        assertThat(py(new Value.Dbl(1e16))).isEqualTo("1e+16");
        assertThat(py(new Value.Dbl(1e15))).isEqualTo("1000000000000000.0");
        assertThat(py(new Value.Dbl(702.567575545))).isEqualTo("702.567575545");
    }

    /** Reference values: repr() in CPython 3.13.13. */
    @ParameterizedTest
    @CsvSource({
        "0.1, 0.1",
        "0.0001, 0.0001",
        "0.00012345, 0.00012345",
        "9.5e-5, 9.5e-05",
        "5e-324, 5e-324",
        "1e23, 1e+23",
        "123456789012345678.0, 1.2345678901234568e+17",
        "1.7976931348623157e308, 1.7976931348623157e+308",
        "2.2250738585072014e-308, 2.2250738585072014e-308",
        "-0.0, -0.0",
        "0.0, 0.0",
        "-29.7, -29.7",
        "154.43099999999998, 154.43099999999998",
        "1e100, 1e+100",
        "Infinity, inf",
        "-Infinity, -inf",
        "NaN, nan"
    })
    void matchesCPythonRepr(String javaLiteral, String expected) {
        assertThat(Numbers.python(Double.parseDouble(javaLiteral))).isEqualTo(expected);
    }

    @Test
    void textIsItself() {
        assertThat(py(new Value.Text("abc"))).isEqualTo("abc");
        assertThat(py(new Value.Int(-7))).isEqualTo("-7");
    }
}
