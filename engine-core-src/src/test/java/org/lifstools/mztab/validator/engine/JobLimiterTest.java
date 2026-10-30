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
package org.lifstools.mztab.validator.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobLimiterTest {

    @Test
    void refusesAJobOverTheLimitWithoutWaiting() {
        JobLimiter limiter = new JobLimiter(1);
        try (JobLimiter.Permit held = limiter.tryAcquire().orElseThrow()) {
            assertThat(limiter.tryAcquire()).isEmpty();
        }
    }

    @Test
    void runsUpToTheLimitAtOnce() {
        JobLimiter limiter = new JobLimiter(2);
        Optional<JobLimiter.Permit> first = limiter.tryAcquire();
        Optional<JobLimiter.Permit> second = limiter.tryAcquire();
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(limiter.tryAcquire()).isEmpty();
        assertThat(limiter.maxConcurrent()).isEqualTo(2);
    }

    @Test
    void closingAPermitFreesItsSlot() {
        JobLimiter limiter = new JobLimiter(1);
        limiter.tryAcquire().orElseThrow().close();
        assertThat(limiter.tryAcquire()).isPresent();
    }

    @Test
    void closingAPermitTwiceFreesOneSlot() {
        JobLimiter limiter = new JobLimiter(1);
        JobLimiter.Permit permit = limiter.tryAcquire().orElseThrow();
        permit.close();
        permit.close();
        try (JobLimiter.Permit held = limiter.tryAcquire().orElseThrow()) {
            assertThat(limiter.tryAcquire()).isEmpty();
        }
    }

    @Test
    void rejectsALimitBelowOne() {
        assertThatThrownBy(() -> new JobLimiter(0)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("engine.max-concurrent-jobs");
    }
}
