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
package org.lifstools.mztab.validator.webapp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Stands between the webapp and the real mzTab-M 2.0 engine of
 * {@link EngineTestSupport}: it forwards every request, records the jobs
 * (every request but {@code GET /engine/v1/info}) and can play an engine that
 * is down or busy. Tests register it as the webapp's 2.0 engine:
 *
 * <pre>
 * static final EngineProxy ENGINE = EngineProxy.start();
 * ...
 * ENGINE.register(registry);
 * </pre>
 */
public final class EngineProxy {

    /** How the proxy answers. */
    public enum Mode {
        /** Like the engine behind it. */
        FORWARD,
        /** Closes every connection at once, as an engine that is not running. */
        DOWN,
        /** {@code 503 busy} with {@code Retry-After}, as an engine running as many jobs as it may. */
        BUSY
    }

    /**
     * Holds the next validation request at the proxy until {@link #release()}:
     * the webapp has sent the text, the engine has not answered yet.
     */
    public static final class Gate {

        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        /** @return whether a validation request arrived within the timeout */
        public boolean awaitArrival(Duration timeout) throws InterruptedException {
            return arrived.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** Forwards the held request. */
        public void release() {
            released.countDown();
        }
    }

    /** A recorded job request. */
    public record Recorded(String method, String path, String contentType, byte[] body) {

        /**
         * The content of a part of a {@code multipart/form-data} body.
         *
         * @return empty if the body has no part of that name
         */
        public Optional<byte[]> part(String name) {
            String boundary = "--" + contentType.replaceFirst(".*boundary=\"?([^\";]+)\"?.*", "$1");
            byte[] marker = ("name=\"" + name + "\"").getBytes(StandardCharsets.US_ASCII);
            int header = indexOf(body, marker, 0);
            if (header < 0) {
                return Optional.empty();
            }
            int start = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), header) + 4;
            int end = indexOf(body, ("\r\n" + boundary).getBytes(StandardCharsets.US_ASCII), start);
            return Optional.of(Arrays.copyOfRange(body, start, end));
        }

        private static int indexOf(byte[] data, byte[] pattern, int from) {
            outer:
            for (int i = from; i <= data.length - pattern.length; i++) {
                for (int j = 0; j < pattern.length; j++) {
                    if (data[i + j] != pattern[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    private final MockWebServer server = new MockWebServer();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Recorded> jobs = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.FORWARD;
    private volatile int retryAfterSeconds = 7;
    private final AtomicReference<Gate> gate = new AtomicReference<>();

    private EngineProxy() {
    }

    /** Starts a proxy on a free port of the loopback interface. */
    public static EngineProxy start() {
        EngineProxy proxy = new EngineProxy();
        proxy.server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return proxy.answer(request);
            }
        });
        try {
            proxy.server.start(InetAddress.getLoopbackAddress(), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the engine proxy", e);
        }
        return proxy;
    }

    /** Registers this proxy as {@code engines.mztabm[0]}, the 2.0 engine and default line. */
    public void register(DynamicPropertyRegistry registry) {
        registry.add("engines.default-line", () -> "2.0");
        registry.add("engines.mztabm[0].line", () -> "2.0");
        registry.add("engines.mztabm[0].url", this::url);
    }

    public String url() {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getPort();
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    /** The {@code Retry-After} of {@link Mode#BUSY}. */
    public void retryAfterSeconds(int seconds) {
        this.retryAfterSeconds = seconds;
    }

    /** Holds the next {@code POST /engine/v1/validations} until the returned gate is released. */
    public Gate holdNextValidation() {
        Gate g = new Gate();
        gate.set(g);
        return g;
    }

    /** The job requests so far, in arrival order. */
    public List<Recorded> jobs() {
        return List.copyOf(jobs);
    }

    /** Forgets the recorded jobs and forwards again. */
    public void reset() {
        jobs.clear();
        mode = Mode.FORWARD;
        Gate held = gate.getAndSet(null);
        if (held != null) {
            held.release();
        }
    }

    private MockResponse answer(RecordedRequest request) {
        String path = request.getPath();
        byte[] body = request.getBody().clone().readByteArray();
        String contentType = request.getHeader("Content-Type");
        if (!"/engine/v1/info".equals(path)) {
            jobs.add(new Recorded(request.getMethod(), path, contentType, body));
        }
        if (path != null && path.startsWith("/engine/v1/validations")) {
            Gate held = gate.getAndSet(null);
            if (held != null) {
                held.arrived.countDown();
                try {
                    if (!held.released.await(2, TimeUnit.MINUTES)) {
                        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
                }
            }
        }
        switch (mode) {
            case DOWN:
                return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
            case BUSY:
                return new MockResponse().setResponseCode(503).setHeader("Retry-After", retryAfterSeconds)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":\"busy\",\"message\":\"The engine is busy.\"}");
            default:
                return forward(request.getMethod(), path, contentType, body);
        }
    }

    private MockResponse forward(String method, String path, String contentType, byte[] body) {
        URI target = EngineTestSupport.engine20().resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(2));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        builder.method(method, body.length == 0 && "GET".equals(method) ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            MockResponse answer = new MockResponse().setResponseCode(response.statusCode());
            response.headers().firstValue("Content-Type").ifPresent(v -> answer.setHeader("Content-Type", v));
            response.headers().firstValue("Retry-After").ifPresent(v -> answer.setHeader("Retry-After", v));
            return answer.setBody(new okio.Buffer().write(response.body()));
        } catch (IOException e) {
            return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
        }
    }
}
