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
package org.lifstools.mztab.validator.webapp.service.validation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SynchronousLimiterTest {

    @Test
    void refusesAConversionOverTheLimitAfterWaiting() {
        SynchronousLimiter limiter = new SynchronousLimiter(1, Duration.ofMillis(50));
        try (SynchronousLimiter.Permit held = limiter.acquire()) {
            assertThatThrownBy(limiter::acquire).isInstanceOf(SynchronousLimiter.BusyException.class);
        }
    }

    @Test
    void closingAPermitFreesItsSlot() {
        SynchronousLimiter limiter = new SynchronousLimiter(1, Duration.ofMillis(50));
        limiter.acquire().close();
        assertThatCode(() -> limiter.acquire().close()).doesNotThrowAnyException();
    }

    @Test
    void closingAPermitTwiceFreesOneSlot() {
        SynchronousLimiter limiter = new SynchronousLimiter(1, Duration.ofMillis(50));
        SynchronousLimiter.Permit permit = limiter.acquire();
        permit.close();
        permit.close();
        try (SynchronousLimiter.Permit held = limiter.acquire()) {
            assertThatThrownBy(limiter::acquire).isInstanceOf(SynchronousLimiter.BusyException.class);
        }
    }
}
