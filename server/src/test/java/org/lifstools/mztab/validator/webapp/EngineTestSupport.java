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
 * Runs the real mzTab-M engines for the server tests: each engine's jar, built
 * by the reactor before the server, is started once per JVM on a free port
 * and stopped when the JVM exits. Tests register the 2.0 engine as the
 * webapp's engine from their {@code @DynamicPropertySource}:
 *
 * <pre>
 * EngineTestSupport.register(registry);
 * </pre>
 *
 * or both engines with {@link #registerBoth}. The jar paths come from the
 * surefire properties {@value #JAR_PROPERTY} and {@value #JAR_21_PROPERTY};
 * the jars exist only after {@code package} of engine-mztabm-2.0 and
 * engine-mztabm-2.1, so run the server tests with {@value #COMMAND}. The 2.1
 * engine is left out of a build with {@code -DskipSnapshotEngines}; then
 * {@link #has21()} is false and its property is not set.
 *
 * <p>The engines ask the {@link OlsStub} in this JVM instead of the EBI
 * Ontology Lookup Service ({@code engine.ols.*}), so semantic validation gives
 * the same result online and offline. To keep it that way the engines run
 * without a network beyond the loopback interface: every other connection
 * goes to a SOCKS proxy on a closed local port and is refused, so a request
 * that escapes the stub fails the validation instead of quietly reaching EBI.
 */
public final class EngineTestSupport {

    static final String JAR_PROPERTY = "engine20.jar";
    static final String JAR_21_PROPERTY = "engine21.jar";
    static final String COMMAND = "./mvnw -B -pl server -am verify -Dfrontend.skip=true";
    private static final Duration STARTUP = Duration.ofSeconds(120);

    private static final java.util.Map<String, URI> urls = new java.util.HashMap<>();
    private static OlsStub ols;

    private EngineTestSupport() {
    }

    /** Registers the running 2.0 engine as {@code engines.mztabm[0]}, starting it if needed. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("engines.default-line", () -> "2.0");
        registry.add("engines.mztabm[0].line", () -> "2.0");
        registry.add("engines.mztabm[0].url", () -> engine20().toString());
    }

    /**
     * Registers the 2.0 engine as {@code engines.mztabm[0]} and, if its jar was built ({@link #has21()}), the 2.1
     * engine as {@code engines.mztabm[1]}; each is started when first needed.
     */
    public static void registerBoth(DynamicPropertyRegistry registry) {
        register(registry);
        if (has21()) {
            registry.add("engines.mztabm[1].line", () -> "2.1");
            registry.add("engines.mztabm[1].url", () -> engine21().toString());
        }
    }

    /** Whether the 2.1 engine's jar was built, i.e. the build did not skip the snapshot engines. */
    public static boolean has21() {
        String property = System.getProperty(JAR_21_PROPERTY);
        return property != null && !property.isBlank() && Files.isRegularFile(Paths.get(property));
    }

    /** The OLS stub the running engine asks; starts the engine if needed. */
    public static synchronized OlsStub ols() {
        engine20();
        return ols;
    }

    /** The base URL of the running 2.0 engine. */
    public static synchronized URI engine20() {
        return urls.computeIfAbsent("2.0", line -> start(line, JAR_PROPERTY));
    }

    /** The base URL of the running 2.1 engine. */
    public static synchronized URI engine21() {
        return urls.computeIfAbsent("2.1", line -> start(line, JAR_21_PROPERTY));
    }

    private static URI start(String line, String jarProperty) {
        String property = System.getProperty(jarProperty);
        if (property == null || property.isBlank() || !Files.isRegularFile(Paths.get(property))) {
            throw new IllegalStateException("The mzTab-M " + line + " engine jar " + (property == null
                ? "(system property " + jarProperty + " is not set)" : property + " does not exist")
                + ". Build it with the server tests: " + COMMAND);
        }
        Path jar = Paths.get(property).toAbsolutePath();
        int port = freePort();
        Path log = jar.getParent().resolve("engine-" + line + "-server-tests.log");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        if (ols == null) {
            try {
                ols = new OlsStub();
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the OLS stub", e);
            }
        }
        ProcessBuilder builder = new ProcessBuilder(List.of(java, "-Xmx512m",
            "-DsocksProxyHost=127.0.0.1", "-DsocksProxyPort=" + freePort(),
            "-DsocksNonProxyHosts=localhost|127.*|[::1]",
            "-jar", jar.toString(),
            "--server.port=" + port, "--spring.main.banner-mode=off",
            "--engine.ols.protocol=http", "--engine.ols.host=" + ols.hostAndPort()))
            .redirectErrorStream(true).redirectOutput(log.toFile());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the mzTab-M " + line + " engine " + jar, e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        URI base = URI.create("http://localhost:" + port);
        awaitInfo(base, log, process, line);
        return base;
    }

    private static void awaitInfo(URI base, Path log, Process process, String line) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest info = HttpRequest.newBuilder(base.resolve("/engine/v1/info")).timeout(Duration.ofSeconds(5))
            .GET().build();
        Instant deadline = Instant.now().plus(STARTUP);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException("The mzTab-M " + line + " engine exited with " + process.exitValue()
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
                throw new IllegalStateException("Interrupted while waiting for the mzTab-M " + line + " engine", e);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the mzTab-M " + line + " engine", e);
            }
        }
        process.destroy();
        throw new IllegalStateException("The mzTab-M " + line + " engine did not answer within " + STARTUP.toSeconds()
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
            throw new IllegalStateException("No free port for an mzTab-M engine", e);
        }
    }
}
