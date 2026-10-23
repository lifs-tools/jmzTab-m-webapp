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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Downloads the configured example files. It must only be called with the URI from the example configuration,
 * never with a client-supplied one.
 */
@Component
public class ExampleFetcher {

    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final Duration DEADLINE = Duration.ofSeconds(60);

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

    @Autowired
    public ExampleFetcher() {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build(), MAX_BYTES, DEADLINE, false);
    }

    /** For tests: plain http can be allowed, production wiring is https only. */
    ExampleFetcher(HttpClient client, long maxBytes, Duration deadline, boolean allowHttp) {
        this.client = client;
        this.maxBytes = maxBytes;
        this.deadline = deadline;
        this.allowHttp = allowHttp;
    }

    /**
     * Downloads the complete file into a temporary file before anything is stored, so a slow download never holds
     * other locks. The whole download (headers and body) must finish within the deadline and the size cap.
     *
     * @return the temporary file; the caller deletes it
     * @throws ExampleFetchException on every failure; no temporary file is left behind then
     */
    public Path download(URI uri) throws ExampleFetchException {
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
