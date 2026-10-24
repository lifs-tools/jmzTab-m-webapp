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
package org.lifstools.mztab.validator.webapp.spa;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

/**
 * Sends client-side routes of the single-page app to {@link SpaController#index}. It runs after all controller,
 * actuator and springdoc mappings and just before the static resource handler, and only takes GET and HEAD requests
 * whose last path segment has no file extension and that are not below {@code /rest}, {@code /v3},
 * {@code /swagger-ui} or {@code /actuator}. Everything else falls through to the static resources (and their 404).
 */
@Component
public class SpaFallbackHandlerMapping extends AbstractHandlerMapping {

    /** First path segments that never belong to the SPA. */
    static final Set<String> EXCLUDED = Set.of("rest", "v3", "swagger-ui", "actuator");

    private final HandlerMethod index;

    public SpaFallbackHandlerMapping(SpaController controller) throws NoSuchMethodException {
        // static resources are mapped at LOWEST_PRECEDENCE - 1
        setOrder(Ordered.LOWEST_PRECEDENCE - 2);
        this.index = new HandlerMethod(controller, SpaController.class.getMethod("index", HttpServletRequest.class));
    }

    @Override
    @Nullable
    protected Object getHandlerInternal(HttpServletRequest request) {
        String method = request.getMethod();
        if (!HttpMethod.GET.matches(method) && !HttpMethod.HEAD.matches(method)) {
            return null;
        }
        return isClientRoute(initLookupPath(request)) ? index : null;
    }

    /** @param path the path within the application, starting with {@code /} */
    static boolean isClientRoute(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String first = slash < 0 ? trimmed : trimmed.substring(0, slash);
        if (EXCLUDED.contains(first)) {
            return false;
        }
        String last = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        return last.indexOf('.') < 0;
    }
}
