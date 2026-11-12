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
package org.lifstools.mztab2.server.api.validation;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Downloads the configured example files. It must only be called with the URI from the example configuration,
 * never with a client-supplied one. A bundled tutorial file ({@code classpath:tutorial/<name>}) is copied from the
 * class path instead; no other {@code classpath:} URI is accepted.
 */
@Component
public class ExampleFetcher {

    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final Duration DEADLINE = Duration.ofSeconds(60);

    /** The scheme of the bundled tutorial files. */
    static final String CLASSPATH = "classpath";
    /** The class path directory of the bundled tutorial files, as named in their URIs. */
    static final String TUTORIAL_ROOT = "tutorial/";
    /** A file name directly in the tutorial directory: no slash, no percent escape; dots only are refused apart. */
    private static final Pattern TUTORIAL_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "example-fetch-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** A download failed, timed out or exceeded the size limit. */
    public static class ExampleFetchException extends IOException {

        public ExampleFetchException(String message) {
            super(message);
        }

        public ExampleFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient client;
    private final long maxBytes;
    private final Duration deadline;
    private final boolean allowHttp;
    private final String tutorialRoot;

    @Autowired
    public ExampleFetcher() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build(), MAX_BYTES, DEADLINE, false);
    }

    /** For tests: plain http can be allowed, production wiring is https only. */
    ExampleFetcher(HttpClient client, long maxBytes, Duration deadline, boolean allowHttp) {
        this(client, maxBytes, deadline, allowHttp, TUTORIAL_ROOT);
    }

    /**
     * For tests: {@code tutorialRoot} is the class path directory that {@code classpath:tutorial/} URIs are read
     * from, so test fixtures need not live next to the real tutorial files.
     */
    ExampleFetcher(HttpClient client, long maxBytes, Duration deadline, boolean allowHttp, String tutorialRoot) {
        this.client = client;
        this.maxBytes = maxBytes;
        this.deadline = deadline;
        this.allowHttp = allowHttp;
        this.tutorialRoot = tutorialRoot;
    }

    /** Whether the URI names a class path resource, valid or not: such a URI is never fetched over the network. */
    static boolean isClasspath(URI uri) {
        return uri != null && uri.getScheme() != null && uri.getScheme().equalsIgnoreCase(CLASSPATH);
    }

    /**
     * The file name of a bundled tutorial file, {@code <name>} of {@code classpath:tutorial/<name>}. The URI is
     * checked in its raw form, so a percent escape cannot smuggle in a slash or dots.
     *
     * @throws ExampleFetchException when the URI is not exactly of that form
     */
    static String tutorialName(URI uri) throws ExampleFetchException {
        if (uri == null || !CLASSPATH.equals(uri.getScheme()) || !uri.isOpaque() || uri.getRawFragment() != null) {
            throw new ExampleFetchException("Only classpath:" + TUTORIAL_ROOT + "<name> URIs name bundled files.");
        }
        String part = uri.getRawSchemeSpecificPart();
        String name = part.startsWith(TUTORIAL_ROOT) ? part.substring(TUTORIAL_ROOT.length()) : "";
        if (!TUTORIAL_NAME.matcher(name).matches() || name.chars().allMatch(c -> c == '.')) {
            throw new ExampleFetchException("Only classpath:" + TUTORIAL_ROOT + "<name> URIs name bundled files.");
        }
        return name;
    }

    /**
     * Copies a bundled tutorial file into a temporary file.
     *
     * @throws ExampleFetchException when the URI is not a tutorial file or the file is missing
     */
    private Path copyBundled(URI uri) throws ExampleFetchException {
        String resource = tutorialRoot + tutorialName(uri);
        Path tmp = null;
        try (InputStream in = ExampleFetcher.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ExampleFetchException("The bundled example file is missing.");
            }
            tmp = Files.createTempFile("example-", ".tmp");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp;
        } catch (ExampleFetchException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw new ExampleFetchException("The bundled example file could not be read.", e);
        }
    }

    /**
     * Downloads the complete file into a temporary file before anything is stored, so a slow download never holds
     * other locks. The whole download (headers and body) must finish within the deadline and the size cap.
     *
     * @return the temporary file; the caller deletes it
     * @throws ExampleFetchException on every failure; no temporary file is left behind then
     */
    public Path download(URI uri) throws ExampleFetchException {
        if (isClasspath(uri)) {
            return copyBundled(uri);
        }
        String scheme = uri == null ? null : uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || allowHttp && scheme.equalsIgnoreCase("http"))) {
            throw new ExampleFetchException("Only https example files can be fetched.");
        }
        long end = System.nanoTime() + deadline.toNanos();
        Path tmp = null;
        AtomicReference<InputStream> open = new AtomicReference<>();
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            InputStream in = open.get();
            if (in != null) {
                try {
                    in.close();
                } catch (IOException | RuntimeException ignored) {
                    // unblocks the reader, which then fails
                }
            }
        }, deadline.toNanos(), TimeUnit.NANOSECONDS);
        try {
            tmp = Files.createTempFile("example-", ".tmp");
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(deadline).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                open.set(in);
                int code = response.statusCode();
                if (code < 200 || code > 299) {
                    throw new ExampleFetchException("Unexpected status " + code + " for the example file.");
                }
                if (response.headers().firstValueAsLong("Content-Length").orElse(0L) > maxBytes) {
                    throw new ExampleFetchException("The example file is too large.");
                }
                try (var out = Files.newOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        total += n;
                        if (total > maxBytes) {
                            throw new ExampleFetchException("The example file is too large.");
                        }
                        if (System.nanoTime() - end > 0) {
                            throw new ExampleFetchException("The example file download timed out.");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
            if (System.nanoTime() - end > 0) {
                throw new ExampleFetchException("The example file download timed out.");
            }
            return tmp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(tmp);
            throw new ExampleFetchException("Interrupted while fetching the example file.", e);
        } catch (ExampleFetchException e) {
            deleteQuietly(tmp);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            throw new ExampleFetchException("The example file could not be fetched.", e);
        } finally {
            watchdog.cancel(false);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
