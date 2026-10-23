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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Downloads the configured example files. It must only be called with the URI from the example configuration,
 * never with a client-supplied one.
 */
@Component
public class ExampleFetcher {

    static final long MAX_BYTES = 64L * 1024 * 1024;

    /** A download failed or exceeded the size limit. */
    public static class ExampleFetchException extends IOException {

        public ExampleFetchException(String message) {
            super(message);
        }

        public ExampleFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10)).build();

    /** @return the content; the caller closes it. Reading beyond 64 MB fails with an {@link ExampleFetchException}. */
    public InputStream fetch(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ExampleFetchException("Only https example files can be fetched.");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExampleFetchException("Interrupted while fetching the example file.", e);
        }
        InputStream body = response.body();
        try {
            int code = response.statusCode();
            if (code < 200 || code > 299) {
                throw new ExampleFetchException("Unexpected status " + code + " for the example file.");
            }
            if (response.headers().firstValueAsLong("Content-Length").orElse(0L) > MAX_BYTES) {
                throw new ExampleFetchException("The example file is too large.");
            }
        } catch (IOException | RuntimeException e) {
            body.close();
            throw e;
        }
        return new LimitedInputStream(body, MAX_BYTES);
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(long n) throws IOException {
            count += n;
            if (count > limit) {
                close();
                throw new ExampleFetchException("The example file is too large.");
            }
        }
    }
}
