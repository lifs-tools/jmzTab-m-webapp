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
package org.lifstools.mztab.validator.webapp.vocabulary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.lifstools.mztab.submission.Vocabulary;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The files of the submission vocabulary, read-only, from the classpath of the
 * {@code mztab-validator-submission-vocabulary} module. Only the published
 * directories and the module's README and LICENSE are served, and only of the
 * version the webapp carries.
 */
@RestController
@RequestMapping("/rest/v2/submission-vocabulary")
@Tag(name = "submission-vocabulary")
public class SubmissionVocabularyController {

    private static final String ROOT = "/submission-vocabulary/";
    private static final Pattern PUBLISHED = Pattern.compile(
        "^(README\\.md|LICENSE|(vocabulary|lists|mappings/(input|output)|schema|examples)/[A-Za-z0-9._/-]+)$");
    private static final MediaType YAML = new MediaType("application", "yaml", StandardCharsets.UTF_8);
    private static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);
    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);
    private static final MediaType PLAIN = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final MediaType TSV = new MediaType("text", "tab-separated-values", StandardCharsets.UTF_8);

    private final String version = Vocabulary.load().version();

    @Operation(operationId = "getSubmissionVocabularyFile", summary = "A file of the submission vocabulary",
        description = "The vocabulary, its lists, mappings, schemas and examples, and the README and LICENSE, of "
            + "the version this webapp carries.")
    @GetMapping("/{version}/{*path}")
    public ResponseEntity<byte[]> file(@Parameter(description = "The vocabulary version, e.g. 1.0.0")
        @PathVariable("version") String requestedVersion,
        @Parameter(description = "The file below the version, e.g. vocabulary/vocabulary.yaml")
        @PathVariable("path") String captured) {
        String path = captured.startsWith("/") ? captured.substring(1) : captured;
        if (!PUBLISHED.matcher(path).matches()
            || Arrays.stream(path.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a file of the submission vocabulary");
        }
        if (!version.equals(requestedVersion)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown vocabulary version");
        }
        byte[] content = read(ROOT + path);
        return ResponseEntity.ok().contentType(mediaType(path))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic()).body(content);
    }

    private static byte[] read(String resource) {
        URL url = SubmissionVocabularyController.class.getResource(resource);
        if (url == null || isDirectory(url)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file");
        }
        try (InputStream in = url.openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A directory of an exploded classpath, or an entry of a jar that names one. */
    private static boolean isDirectory(URL url) {
        try {
            if ("file".equals(url.getProtocol())) {
                return Files.isDirectory(Path.of(url.toURI()));
            }
            if (url.openConnection() instanceof JarURLConnection jar) {
                jar.setUseCaches(false);
                try (JarFile file = jar.getJarFile()) {
                    JarEntry entry = jar.getJarEntry();
                    if (entry == null || entry.isDirectory()) {
                        return true;
                    }
                    // a jar need not hold entries for its directories: anything below the name makes it one
                    String below = entry.getName() + "/";
                    return file.stream().anyMatch(e -> e.getName().startsWith(below));
                }
            }
            return false;
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MediaType mediaType(String path) {
        if (path.equals("LICENSE")) {
            return PLAIN;
        }
        if (path.endsWith(".yaml")) {
            return YAML;
        }
        if (path.endsWith(".json")) {
            return JSON;
        }
        if (path.endsWith(".md")) {
            return MARKDOWN;
        }
        if (path.endsWith(".mzTab")) {
            return TSV;
        }
        return PLAIN;
    }
}
