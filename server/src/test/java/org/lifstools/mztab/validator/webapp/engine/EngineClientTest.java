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
package org.lifstools.mztab.validator.webapp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lifstools.mztab.validator.engine.api.EngineErrorCode;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;

class EngineClientTest {

    private MockWebServer server;

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private EngineClient client(Duration jobTimeout) {
        return new EngineClient("2.0", server.url("/").uri(), Duration.ofSeconds(2), jobTimeout);
    }

    private static String drain(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Path file(String content) throws IOException {
        Path f = Files.createTempFile(tmp, "in", ".mztab");
        Files.writeString(f, content);
        return f;
    }

    @Test
    void infoIsParsed() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(
            "{\"line\":\"2.0\",\"mzTabVersions\":[\"2.0.0-M\"],\"jmztabmVersion\":\"1.0.8\",\"build\":\"b\","
            + "\"maxUploadBytes\":67108864,\"maxConcurrentJobs\":4}"));
        EngineInfo info = client(Duration.ofSeconds(5)).info();
        assertEquals("2.0", info.line());
        assertEquals("1.0.8", info.jmztabmVersion());
        assertEquals(4, info.maxConcurrentJobs());
        assertEquals("/engine/v1/info", server.takeRequest().getPath());
    }

    @Test
    void validateSendsMultipartPartsAndQueryAndStreamsResponse() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/x-ndjson")
            .setBody("{\"type\":\"end\"}\n"));
        String result = client(Duration.ofSeconds(5)).validate(file("MTD\tmzTab-version\t2.0.0-M\n"),
            file("<mapping/>"), ValidationLevel.WARN, 50, EngineClientTest::drain);
        assertEquals("{\"type\":\"end\"}\n", result);
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/engine/v1/validations?level=WARN&maxErrors=50", request.getPath());
        assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data"));
        assertTrue(request.getHeader("Accept").contains("application/x-ndjson"));
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("name=\"file\""), body);
        assertTrue(body.contains("name=\"mapping\""), body);
        assertTrue(body.contains("mzTab-version"), body);
    }

    @Test
    void validateWithoutMappingSendsOnlyFile() throws Exception {
        server.enqueue(new MockResponse().setBody("x"));
        client(Duration.ofSeconds(5)).validate(file("a"), null, ValidationLevel.INFO, 100, EngineClientTest::drain);
        String body = server.takeRequest().getBody().readUtf8();
        assertFalse(body.contains("name=\"mapping\""), body);
    }

    @Test
    void busyMapsToEngineBusyExceptionWithRetryAfter() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "7")
            .setHeader("Content-Type", "application/json").setBody("{\"code\":\"busy\",\"message\":\"busy\"}"));
        EngineBusyException e = assertThrows(EngineBusyException.class,
            () -> client(Duration.ofSeconds(5)).validate(file("a"), null, ValidationLevel.INFO, 100,
                EngineClientTest::drain));
        assertEquals(Duration.ofSeconds(7), e.retryAfter());
    }

    @Test
    void busyWithoutRetryAfterGetsDefault() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        EngineBusyException e = assertThrows(EngineBusyException.class,
            () -> client(Duration.ofSeconds(5)).tsvToJson(new ByteArrayInputStream(new byte[0]), tmp.resolve("o")));
        assertEquals(Duration.ofSeconds(5), e.retryAfter());
    }

    @Test
    void connectionRefusedMapsToUnavailable() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        EngineClient c = new EngineClient("2.0", URI.create("http://localhost:" + port), Duration.ofSeconds(2),
            Duration.ofSeconds(5));
        EngineUnavailableException e = assertThrows(EngineUnavailableException.class,
            () -> c.validate(file("a"), null, ValidationLevel.INFO, 100, EngineClientTest::drain));
        assertEquals("2.0", e.line());
        assertThrows(EngineUnavailableException.class, c::info);
    }

    @Test
    void slowEngineTimesOut() throws Exception {
        server.enqueue(new MockResponse().setBody("late").setHeadersDelay(3, TimeUnit.SECONDS));
        EngineClient c = client(Duration.ofSeconds(1));
        assertThrows(EngineUnavailableException.class,
            () -> c.validate(file("a"), null, ValidationLevel.INFO, 100, EngineClientTest::drain));
    }

    @Test
    void serverErrorMapsToUnavailable() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"code\":\"internal\",\"message\":\"x\"}"));
        assertThrows(EngineUnavailableException.class, () -> client(Duration.ofSeconds(5)).info());
    }

    @Test
    void truncatedBodyReadFailureMapsToUnavailable() throws Exception {
        server.enqueue(new MockResponse().setBody("x"));
        assertThrows(EngineUnavailableException.class,
            () -> client(Duration.ofSeconds(5)).validate(file("a"), null, ValidationLevel.INFO, 100, in -> {
                throw new java.io.UncheckedIOException(new IOException("stream ended"));
            }));
    }

    @Test
    void badRequestAndTooLargeMapToEngineRequestException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"unsupported_version\",\"message\":\"other line\"}"));
        server.enqueue(new MockResponse().setResponseCode(413).setHeader("Content-Type", "application/json")
            .setBody("{\"code\":\"file_too_large\",\"message\":\"too big\"}"));
        EngineClient c = client(Duration.ofSeconds(5));
        EngineRequestException e = assertThrows(EngineRequestException.class,
            () -> c.validate(file("a"), null, ValidationLevel.INFO, 100, EngineClientTest::drain));
        assertEquals(EngineErrorCode.UNSUPPORTED_VERSION, e.error().code());
        assertEquals("other line", e.error().message());
        EngineRequestException e2 = assertThrows(EngineRequestException.class,
            () -> c.tsvToJson(new ByteArrayInputStream(new byte[0]), tmp.resolve("o")));
        assertEquals(EngineErrorCode.FILE_TOO_LARGE, e2.error().code());
    }

    @Test
    void unparseableErrorBodyStillMapsToRequestException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("<html>no</html>"));
        EngineRequestException e = assertThrows(EngineRequestException.class,
            () -> client(Duration.ofSeconds(5)).jsonToTsv(new ByteArrayInputStream(new byte[0]), true, tmp.resolve("o")));
        assertEquals(EngineErrorCode.INVALID_REQUEST, e.error().code());
    }

    @Test
    void tsvToJsonWritesTargetAndSendsTsvBody() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"a\":1}"));
        Path target = tmp.resolve("out.json");
        Path result = client(Duration.ofSeconds(5)).tsvToJson(
            new ByteArrayInputStream("MTD\tx\ty\n".getBytes(StandardCharsets.UTF_8)), target);
        assertEquals(target, result);
        assertEquals("{\"a\":1}", Files.readString(target));
        RecordedRequest request = server.takeRequest();
        assertEquals("/engine/v1/conversions/tsv-to-json", request.getPath());
        assertTrue(request.getHeader("Content-Type").startsWith("text/tab-separated-values"));
        assertEquals("MTD\tx\ty\n", request.getBody().readUtf8());
    }

    @Test
    void jsonToTsvPassesValidateFlag() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/tab-separated-values").setBody("MTD\ta\tb\n"));
        Path target = tmp.resolve("out.mztab");
        client(Duration.ofSeconds(5)).jsonToTsv(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), false,
            target);
        assertEquals("MTD\ta\tb\n", Files.readString(target));
        RecordedRequest request = server.takeRequest();
        assertEquals("/engine/v1/conversions/json-to-tsv?validate=false", request.getPath());
        assertTrue(request.getHeader("Content-Type").startsWith("application/json"));
    }

    @Test
    void unprocessableMapsToRejectionAndLeavesNoTarget() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json").setBody(
            "[{\"code\":\"Error-1\",\"category\":\"format\",\"message_type\":\"error\",\"message\":\"bad\","
            + "\"line_number\":3}]"));
        Path target = tmp.resolve("never");
        EngineRejection e = assertThrows(EngineRejection.class, () -> client(Duration.ofSeconds(5)).tsvToJson(
            new ByteArrayInputStream(new byte[0]), target));
        assertEquals(1, e.messages().size());
        assertEquals("bad", e.messages().get(0).getMessage());
        assertFalse(Files.exists(target));
    }

    @Test
    void uploadIsStreamedNotBuffered() throws Exception {
        // a child JVM with a 64 MB heap uploads a 40 MB file to a receiver in the same JVM (which holds the 40 MB
        // body once): a client that buffered the file and its multipart copy could not fit
        Path big = tmp.resolve("big.mztab");
        try (java.io.OutputStream out = Files.newOutputStream(big)) {
            byte[] line = ("COM\t" + "x".repeat(1019) + "\n").getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 40 * 1024; i++) {
                out.write(line);
            }
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-Xmx64m", "-XX:+ExitOnOutOfMemoryError", "-cp", System.getProperty("java.class.path"),
            UploadMain.class.getName(), big.toString()).redirectErrorStream(true).start();
        boolean finished = process.waitFor(1, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
        long fileSize = Files.size(big);
        assertTrue(output.contains("chunked=true"), output);
        long bodySize = Long.parseLong(output.replaceAll("(?s).*bodySize=(\\d+).*", "$1").strip());
        assertTrue(bodySize >= fileSize && bodySize < fileSize + 2000, output);
    }

    /** Runs in a child JVM with a small heap: uploads a file and reports what the server received. */
    public static final class UploadMain {

        public static void main(String[] args) throws Exception {
            Path file = Path.of(args[0]);
            try (MockWebServer s = new MockWebServer()) {
                s.start();
                s.enqueue(new MockResponse().setBody("ok"));
                EngineClient c = new EngineClient("2.0", s.url("/").uri(), Duration.ofSeconds(5), Duration.ofMinutes(2));
                c.validate(file, null, ValidationLevel.INFO, 10, in -> {
                    try (in) {
                        return in.readAllBytes().length;
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                RecordedRequest r = s.takeRequest();
                System.out.println("chunked=" + (r.getHeader("Content-Length") == null));
                System.out.println("bodySize=" + r.getBodySize());
            }
        }
    }
}
