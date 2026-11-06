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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry.RouteDecision;
import org.slf4j.LoggerFactory;

class EngineRegistryTest {

    private static EnginesProperties props(String defaultLine, String... lines) {
        List<EnginesProperties.Engine> engines = java.util.Arrays.stream(lines)
            .map(l -> new EnginesProperties.Engine(l, URI.create("http://localhost:1/" + l))).toList();
        return new EnginesProperties(defaultLine, engines, Duration.ofSeconds(5), Duration.ofMinutes(10), null);
    }

    @Test
    void routesDeclaredVersionToItsLine() {
        EngineRegistry registry = new EngineRegistry(props("2.0", "2.0", "2.1"));
        RouteDecision decision = registry.route(Optional.of("2.1.0-M"));
        RouteDecision.Routed routed = assertInstanceOf(RouteDecision.Routed.class, decision);
        assertEquals("2.1", routed.line());
        assertEquals(false, routed.defaultApplied());
        assertEquals("2.1", routed.engine().line());
    }

    @Test
    void missingVersionUsesDefaultLine() {
        EngineRegistry registry = new EngineRegistry(props("2.0", "2.0", "2.1"));
        RouteDecision.Routed routed = assertInstanceOf(RouteDecision.Routed.class, registry.route(Optional.empty()));
        assertEquals("2.0", routed.line());
        assertTrue(routed.defaultApplied());
    }

    @Test
    void legacyVersionIsLegacy() {
        EngineRegistry registry = new EngineRegistry(props("2.0", "2.0", "2.1"));
        assertEquals(new RouteDecision.Legacy("1.0.0", Set.of("2.0", "2.1")), registry.route(Optional.of("1.0.0")));
    }

    @Test
    void lineWithoutEngineIsUnsupported() {
        EngineRegistry registry = new EngineRegistry(props("2.0", "2.0"));
        RouteDecision.Unsupported unsupported = assertInstanceOf(RouteDecision.Unsupported.class,
            registry.route(Optional.of("2.1.0-M")));
        assertEquals("2.1.0-M", unsupported.declared());
        assertEquals(Set.of("2.0"), unsupported.supported());
    }

    @Test
    void unknownLineIsUnsupported() {
        EngineRegistry registry = new EngineRegistry(props("2.0", "2.0", "2.1"));
        assertInstanceOf(RouteDecision.Unsupported.class, registry.route(Optional.of("2.2.0-M")));
        assertInstanceOf(RouteDecision.Unsupported.class, registry.route(Optional.of("garbage")));
    }

    @Test
    void startupFailsWhenDefaultLineHasNoEngine() {
        assertThrows(IllegalStateException.class, () -> new EngineRegistry(props("2.1", "2.0")));
    }

    /** F5: an engine that serves another line than configured (e.g. swapped URLs) is not used as available. */
    @Test
    void engineServingAnotherLineThanConfiguredIsUnavailable() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(EngineRegistry.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody(
                        "{\"line\":\"2.1\",\"mzTabVersions\":[\"2.1.0-M\"],\"jmztabmVersion\":\"2.1.0\","
                        + "\"build\":\"b\",\"maxUploadBytes\":10,\"maxConcurrentJobs\":2}");
                }
            });
            server.start();
            EngineRegistry registry = new EngineRegistry(new EnginesProperties("2.0",
                List.of(new EnginesProperties.Engine("2.0", server.url("/").uri())), Duration.ofSeconds(2),
                Duration.ofMinutes(1), null));

            assertEquals(Optional.empty(), registry.refresh("2.0"));
            assertEquals(Optional.empty(), registry.refresh("2.0"));

            assertEquals(Optional.empty(), registry.lastKnownInfo().get("2.0"));
            List<ILoggingEvent> warnings = logged.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size(), "one warning while the mismatch lasts: " + logged.list);
            assertTrue(warnings.get(0).getFormattedMessage().contains("mzTab-M 2.0")
                && warnings.get(0).getFormattedMessage().contains("mzTab-M 2.1"), warnings.get(0).getFormattedMessage());
        } finally {
            logger.detachAppender(logged);
        }
    }

    @Test
    void forLineAndLines() {
        EngineRegistry registry = new EngineRegistry(props("2.0", "2.0", "2.1"));
        assertEquals(Set.of("2.0", "2.1"), registry.lines());
        assertTrue(registry.forLine("2.1").isPresent());
        assertTrue(registry.forLine("2.2").isEmpty());
        assertEquals("2.0", registry.defaultLine());
        assertEquals(Optional.empty(), registry.lastKnownInfo().get("2.0"));
    }
}
