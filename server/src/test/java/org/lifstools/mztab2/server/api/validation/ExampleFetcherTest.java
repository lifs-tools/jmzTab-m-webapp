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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExampleFetcherTest {

    HttpServer server;
    URI base;
    Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            byte[] b = "hello example".getBytes();
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/missing", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/big", ex -> {
            ex.sendResponseHeaders(200, 0); // chunked, no Content-Length
            ex.getResponseBody().write(new byte[1000]);
            ex.close();
        });
        server.createContext("/stall", ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("partial".getBytes());
            ex.getResponseBody().flush();
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    ExampleFetcher fetcher(long max, Duration deadline) {
        return new ExampleFetcher(HttpClient.newHttpClient(), max, deadline, true);
    }

    long exampleTempFiles() throws IOException {
        try (Stream<Path> s = Files.list(tmpDir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("example-")).count();
        }
    }

    @Test
    void httpIsRejectedByProductionWiring() {
        assertThrows(ExampleFetcher.ExampleFetchException.class,
            () -> new ExampleFetcher().download(base.resolve("/ok")));
    }

    @Test
    void successReturnsFullContent() throws Exception {
        Path p = fetcher(1000, Duration.ofSeconds(10)).download(base.resolve("/ok"));
        try {
            assertArrayEquals("hello example".getBytes(), Files.readAllBytes(p));
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    void non2xxFails() throws Exception {
        long before = exampleTempFiles();
        assertThrows(ExampleFetcher.ExampleFetchException.class,
            () -> fetcher(1000, Duration.ofSeconds(10)).download(base.resolve("/missing")));
        assertEquals(before, exampleTempFiles());
    }

    @Test
    void oversizeFailsAndLeavesNoFile() throws Exception {
        long before = exampleTempFiles();
        assertThrows(ExampleFetcher.ExampleFetchException.class,
            () -> fetcher(100, Duration.ofSeconds(10)).download(base.resolve("/big")));
        assertEquals(before, exampleTempFiles());
    }

    @Test
    void stalledBodyFailsAtDeadline() throws Exception {
        long before = exampleTempFiles();
        long start = System.nanoTime();
        assertThrows(ExampleFetcher.ExampleFetchException.class,
            () -> fetcher(1000, Duration.ofMillis(500)).download(base.resolve("/stall")));
        assertEquals(true, Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5);
        assertEquals(before, exampleTempFiles());
    }
}
