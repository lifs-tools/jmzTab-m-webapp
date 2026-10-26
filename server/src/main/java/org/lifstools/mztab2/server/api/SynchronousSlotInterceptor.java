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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousLimiter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Holds a {@link SynchronousLimiter} slot for the whole request: it is taken
 * before the handler reads the body and freed after the response is written,
 * since the model, the converted file or the messages are serialized after
 * the handler returns. A
 * request that finds no free slot fails with
 * {@link SynchronousLimiter.BusyException}, which {@code RestErrorAdvice}
 * answers with 503.
 */
@Component
public class SynchronousSlotInterceptor implements HandlerInterceptor {

    static final String PERMIT = SynchronousSlotInterceptor.class.getName() + ".permit";

    private final SynchronousLimiter limiter;

    public SynchronousSlotInterceptor(SynchronousLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(PERMIT, limiter.acquire());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
        @Nullable Exception ex) {
        if (request.getAttribute(PERMIT) instanceof SynchronousLimiter.Permit permit) {
            request.removeAttribute(PERMIT);
            permit.close();
        }
    }
}
