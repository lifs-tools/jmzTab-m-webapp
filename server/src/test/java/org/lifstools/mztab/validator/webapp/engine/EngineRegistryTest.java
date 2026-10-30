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

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry.RouteDecision;

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
        assertEquals(new RouteDecision.Legacy("1.0.0"), registry.route(Optional.of("1.0.0")));
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
