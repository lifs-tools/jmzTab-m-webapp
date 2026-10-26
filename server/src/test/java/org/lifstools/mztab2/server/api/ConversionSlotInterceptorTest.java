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
package org.lifstools.mztab2.server.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.webapp.service.validation.ConversionLimiter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A conversion holds its slot until the response is written: the model and
 * the converted file are serialized after the handler returns.
 */
class ConversionSlotInterceptorTest {

    final ConversionLimiter limiter = new ConversionLimiter(1, Duration.ofMillis(50));

    final ConversionSlotInterceptor interceptor = new ConversionSlotInterceptor(limiter);

    final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/rest/v2/convertPlain");

    final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void theSlotIsHeldUntilTheRequestIsComplete() throws Exception {
        interceptor.preHandle(request, response, new Object());
        interceptor.postHandle(request, response, new Object(), null);
        assertThatThrownBy(limiter::acquire).isInstanceOf(ConversionLimiter.BusyException.class);
        interceptor.afterCompletion(request, response, new Object(), null);
        assertThatCode(() -> limiter.acquire().close()).doesNotThrowAnyException();
    }

    @Test
    void aFailedRequestFreesItsSlot() throws Exception {
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), new IllegalStateException("failed"));
        assertThatCode(() -> limiter.acquire().close()).doesNotThrowAnyException();
    }
}
