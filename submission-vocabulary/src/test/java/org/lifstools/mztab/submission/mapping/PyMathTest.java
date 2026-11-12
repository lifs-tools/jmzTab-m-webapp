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

/** Reference values: CPython 3.13.13. */
class PyMathTest {

    @Test
    void fmeanFsumStdev() {
        assertThat(PyMath.fmean(new double[]{2, 2})).isEqualTo(2.0);
        assertThat(PyMath.fsum(new double[]{0.1, 0.2, 0.3})).isEqualTo(0.6);              // naive sum gives 0.6000000000000001
        assertThat(PyMath.stdev(new double[]{1.0, 3.0})).isEqualTo(1.4142135623730951);
    }

    @Test
    void fsumKeepsEveryPartial() {
        // math.fsum([1e100, 1.0, -1e100, 1e-100, 1e50, -1.0, -1e50])
        assertThat(PyMath.fsum(new double[]{1e100, 1.0, -1e100, 1e-100, 1e50, -1.0, -1e50})).isEqualTo(1e-100);
        assertThat(PyMath.fsum(new double[]{})).isEqualTo(0.0);
    }

    @Test
    void stdevIsTheCorrectlyRoundedRootOfTheExactVariance() {
        assertThat(PyMath.stdev(new double[]{0.1, 0.2, 0.4, 0.7})).isEqualTo(0.264575131106459);
        assertThat(PyMath.stdev(new double[]{1, 2, 3, 4, 5, 6, 7})).isEqualTo(2.160246899469287);
        assertThat(PyMath.fmean(new double[]{0.1, 0.2, 0.4})).isEqualTo(0.23333333333333336);
    }

    @Test
    void neumaierSumAsCPythonBuiltinSum() {
        assertThat(PyMath.neumaierSum(new double[]{0.1, 0.2, 0.3})).isEqualTo(0.6);
        assertThat(PyMath.neumaierSum(new double[]{0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1})).isEqualTo(1.0);
        assertThat(PyMath.neumaierSum(new double[]{1e100, 1.0, -1e100})).isEqualTo(1.0);
        assertThat(PyMath.neumaierSum(new double[]{})).isEqualTo(0.0);
    }
}
