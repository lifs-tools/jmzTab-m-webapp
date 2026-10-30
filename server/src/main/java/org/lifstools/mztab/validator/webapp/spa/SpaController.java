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

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the single-page app's {@code index.html} with its {@code <base href>} set to the request's context path,
 * so the same build works under {@code /mztabvalidator}, {@code /mztabvalidator-dev} and {@code /mztab-validator}.
 * The root and {@code /index.html} are mapped here; every other client-side route reaches {@link #index} through
 * {@link SpaFallbackHandlerMapping}. The old Thymeleaf URLs {@code /validate/{id}} and
 * {@code /validate/{id}/delete} redirect to the result page; the delete URL never deletes on GET.
 */
@Controller
public class SpaController {

    private static final Logger log = LoggerFactory.getLogger(SpaController.class);

    static final String INDEX_LOCATION = "classpath:/static/index.html";

    /** The base tag of the Angular build: {@code <base href="/" />} or {@code <base href="/">}. */
    private static final Pattern BASE_TAG = Pattern.compile("<base\\s+href=\"/\"\\s*/?>");

    /** Path segments of a context path we are willing to put into the page; anything else is not used. */
    private static final Pattern SAFE_CONTEXT_PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)*");

    /** Bounds the cache: the context path may come from {@code X-Forwarded-Prefix}. */
    private static final int MAX_CACHED_BASES = 16;

    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final ResourceLoader resources;
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private volatile @Nullable String template;

    public SpaController(ResourceLoader resources, ServletContext servletContext) {
        this.resources = resources;
        // the server's own context path always has a cache slot, whatever prefixes arrive first
        String own = servletContext.getContextPath();
        if (own != null && SAFE_CONTEXT_PATH.matcher(own).matches()) {
            page(own);
        }
    }

    /** The context paths with a cached page, for tests. */
    Set<String> cachedContextPaths() {
        return Set.copyOf(pages.keySet());
    }

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<String> index(HttpServletRequest request) {
        String page = page(contextPathOf(request));
        if (page == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN)
                .body("The web interface is not part of this build.");
        }
        return ResponseEntity.ok()
            .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
            .cacheControl(CacheControl.noCache())
            .body(page);
    }

    @GetMapping("/validate/{id:" + UUID_PATTERN + "}")
    public ResponseEntity<Void> oldValidateUrl(@PathVariable String id, HttpServletRequest request) {
        return redirect(contextPathOf(request) + "/result/" + id);
    }

    @GetMapping("/validate/{id:" + UUID_PATTERN + "}/delete")
    public ResponseEntity<Void> oldDeleteUrl(@PathVariable String id, HttpServletRequest request) {
        return redirect(contextPathOf(request) + "/result/" + id + "?confirmDelete=true");
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    /**
     * The request's context path if it is a plain path, else the server's own one. With
     * {@code server.forward-headers-strategy=FRAMEWORK} the request's context path comes from
     * {@code X-Forwarded-Prefix}, which must never put a foreign host or markup into the page.
     */
    static String contextPathOf(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        if (contextPath != null && SAFE_CONTEXT_PATH.matcher(contextPath).matches()) {
            return contextPath;
        }
        if (log.isDebugEnabled()) {
            log.debug("Ignoring unsafe context path '{}'", printable(contextPath));
        }
        String own = request.getServletContext().getContextPath();
        return own != null && SAFE_CONTEXT_PATH.matcher(own).matches() ? own : "";
    }

    /** A client-supplied value made safe for a log line: no control characters, at most 64 characters. */
    static String printable(@Nullable String value) {
        if (value == null) {
            return "null";
        }
        String clean = value.codePoints()
            .map(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT ? '?' : c)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
        return clean.length() > 64 ? clean.substring(0, 64) + "…" : clean;
    }

    @Nullable
    private String page(String contextPath) {
        String cached = pages.get(contextPath);
        if (cached != null) {
            return cached;
        }
        String index = template();
        if (index == null) {
            return null;
        }
        Matcher base = BASE_TAG.matcher(index);
        if (!base.find()) {
            log.warn("The SPA index.html has no <base href=\"/\"> tag; serving it unchanged");
        }
        String page = base.replaceFirst(Matcher.quoteReplacement("<base href=\"" + contextPath + "/\">"));
        if (pages.size() < MAX_CACHED_BASES) {
            pages.putIfAbsent(contextPath, page);
        }
        return page;
    }

    @Nullable
    private String template() {
        String loaded = template;
        if (loaded == null) {
            Resource index = resources.getResource(INDEX_LOCATION);
            if (!index.exists()) {
                log.warn("{} not found; the frontend was not built into this jar", INDEX_LOCATION);
                return null;
            }
            try (InputStream in = index.getInputStream()) {
                loaded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + INDEX_LOCATION, e);
            }
            template = loaded;
        }
        return loaded;
    }
}
