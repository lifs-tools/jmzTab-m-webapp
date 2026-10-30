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
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Runs the real mzTab-M 2.0 engine for the server tests: its jar, built by the
 * reactor before the server, is started once per JVM on a free port and
 * stopped when the JVM exits. Tests register it as the webapp's 2.0 engine
 * from their {@code @DynamicPropertySource}:
 *
 * <pre>
 * EngineTestSupport.register(registry);
 * </pre>
 *
 * The jar path comes from the surefire property {@value #JAR_PROPERTY}; the jar
 * exists only after {@code package} of engine-mztabm-2.0, so run the server
 * tests with {@value #COMMAND}.
 */
public final class EngineTestSupport {

    static final String JAR_PROPERTY = "engine20.jar";
    static final String COMMAND = "./mvnw -B -pl server -am verify -Dfrontend.skip=true";
    private static final Duration STARTUP = Duration.ofSeconds(120);

    private static URI url;
    private static Process process;

    private EngineTestSupport() {
    }

    /** Registers the running 2.0 engine as {@code engines.mztabm[0]}, starting it if needed. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("engines.default-line", () -> "2.0");
        registry.add("engines.mztabm[0].line", () -> "2.0");
        registry.add("engines.mztabm[0].url", () -> engine20().toString());
    }

    /** The base URL of the running 2.0 engine. */
    public static synchronized URI engine20() {
        if (url == null) {
            url = start();
        }
        return url;
    }

    private static URI start() {
        String property = System.getProperty(JAR_PROPERTY);
        if (property == null || property.isBlank() || !Files.isRegularFile(Paths.get(property))) {
            throw new IllegalStateException("The mzTab-M 2.0 engine jar " + (property == null ? "(system property "
                + JAR_PROPERTY + " is not set)" : property + " does not exist")
                + ". Build it with the server tests: " + COMMAND);
        }
        Path jar = Paths.get(property).toAbsolutePath();
        int port = freePort();
        Path log = jar.getParent().resolve("engine-2.0-server-tests.log");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(List.of(java, "-Xmx512m", "-jar", jar.toString(),
            "--server.port=" + port, "--spring.main.banner-mode=off"))
            .redirectErrorStream(true).redirectOutput(log.toFile());
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the mzTab-M 2.0 engine " + jar, e);
        }
        Process started = process;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            started.destroy();
            try {
                started.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        URI base = URI.create("http://localhost:" + port);
        awaitInfo(base, log);
        return base;
    }

    private static void awaitInfo(URI base, Path log) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest info = HttpRequest.newBuilder(base.resolve("/engine/v1/info")).timeout(Duration.ofSeconds(5))
            .GET().build();
        Instant deadline = Instant.now().plus(STARTUP);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException("The mzTab-M 2.0 engine exited with " + process.exitValue()
                    + ":\n" + tail(log));
            }
            try {
                if (http.send(info, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the mzTab-M 2.0 engine", e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the mzTab-M 2.0 engine", e);
            }
        }
        process.destroy();
        throw new IllegalStateException("The mzTab-M 2.0 engine did not answer within " + STARTUP.toSeconds()
            + " s:\n" + tail(log));
    }

    private static String tail(Path log) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(no log: " + e + ")";
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port for the mzTab-M 2.0 engine", e);
        }
    }
}
