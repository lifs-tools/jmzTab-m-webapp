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

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cache headers for the single-page app's build output. The Angular build puts a content hash into the names of
 * its scripts, styles and media ({@code main-OY5RMYQD.js}, {@code chunk-*.js}, {@code styles-*.css},
 * {@code media/*-HASH.woff2}), so those never change and may be cached for a year. Unhashed public files (the
 * favicon and the logos under {@code brand/}) are cached for a day. {@code index.html} is served by
 * {@link SpaController} with {@code no-cache}; everything else keeps Spring Boot's default static handler.
 * <p>
 * A response that already has {@code Cache-Control} is left alone by Spring Security's cache-control header
 * writer, so its {@code no-store} default still applies to {@code /rest/**} and every other response.
 */
@Configuration
public class SpaResourceConfig implements WebMvcConfigurer {

    static final String STATIC = "classpath:/static/";

    /** Hash part of Angular's output names: upper-case letters and digits. */
    private static final String HASH = "-[A-Z0-9]+\\.";

    static final String HASHED_BUNDLE = "/{file:(?:main|chunk|polyfills|scripts|styles|worker)" + HASH + "(?:js|css)}";
    // a path pattern ends a segment at every '/', so the regex must not contain one
    static final String HASHED_MEDIA = "/media/{file:[A-Za-z0-9._-]+" + HASH + "[A-Za-z0-9]+}";

    static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
    static final CacheControl ONE_DAY = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(HASHED_BUNDLE).addResourceLocations(STATIC).setCacheControl(IMMUTABLE);
        registry.addResourceHandler(HASHED_MEDIA).addResourceLocations(STATIC + "media/").setCacheControl(IMMUTABLE);
        registry.addResourceHandler("/favicon.ico").addResourceLocations(STATIC).setCacheControl(ONE_DAY);
        registry.addResourceHandler("/brand/**").addResourceLocations(STATIC + "brand/").setCacheControl(ONE_DAY);
    }
}
