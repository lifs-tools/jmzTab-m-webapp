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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The configured engines by version line, and the decision which of them a
 * file declaring a given version goes to. Nothing here contacts an engine at
 * startup: what an engine reported about itself is fetched in the background
 * and on demand.
 */
@Slf4j
@Component
public class EngineRegistry {

    /** Where a file goes. */
    public sealed interface RouteDecision {

        /**
         * To the engine of a line.
         *
         * @param defaultApplied whether the file declared no version and the
         * default line was used
         */
        record Routed(EngineClient engine, String line, boolean defaultApplied) implements RouteDecision {

        }

        /**
         * An mzTab 1.x file, which is not validated any more.
         *
         * @param supported the configured lines
         */
        record Legacy(String declared, Set<String> supported) implements RouteDecision {

        }

        /** A version without an engine on this instance. */
        record Unsupported(String declared, Set<String> supported) implements RouteDecision {

        }
    }

    private final Map<String, EngineClient> clients;
    private final String defaultLine;
    private final Map<String, Optional<EngineInfo>> info = new ConcurrentHashMap<>();
    /** The line each engine that serves another line than configured last reported, to warn once. */
    private final Map<String, String> otherLines = new ConcurrentHashMap<>();

    @Autowired
    public EngineRegistry(EnginesProperties properties) {
        Map<String, EngineClient> built = new LinkedHashMap<>();
        for (EnginesProperties.Engine engine : properties.mztabm()) {
            built.put(engine.line(), new EngineClient(engine.line(), engine.url(), properties.connectTimeout(),
                properties.jobTimeout()));
        }
        this.clients = Collections.unmodifiableMap(built);
        this.defaultLine = properties.defaultLine();
        if (!clients.containsKey(defaultLine)) {
            throw new IllegalStateException("engines.default-line is " + defaultLine
                + ", but no engine is configured for it. Configured lines: " + clients.keySet());
        }
        clients.keySet().forEach(line -> info.put(line, Optional.empty()));
    }

    public Optional<EngineClient> forLine(String line) {
        return Optional.ofNullable(clients.get(line));
    }

    /** The configured lines, in configuration order. */
    public Set<String> lines() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(clients.keySet()));
    }

    public String defaultLine() {
        return defaultLine;
    }

    /**
     * Decides where a file goes.
     *
     * @param declared the declared {@code mzTab-version}, empty if there is none
     */
    public RouteDecision route(Optional<String> declared) {
        if (declared.isEmpty()) {
            return new RouteDecision.Routed(clients.get(defaultLine), defaultLine, true);
        }
        String version = declared.get();
        if (MzTabVersions.isLegacy(version)) {
            return new RouteDecision.Legacy(version, lines());
        }
        Optional<String> line = MzTabVersions.lineOf(version);
        if (line.isPresent() && clients.containsKey(line.get())) {
            return new RouteDecision.Routed(clients.get(line.get()), line.get(), false);
        }
        return new RouteDecision.Unsupported(version, lines());
    }

    /** What each engine last reported about itself; empty if it was not reachable the last time. */
    public Map<String, Optional<EngineInfo>> lastKnownInfo() {
        Map<String, Optional<EngineInfo>> copy = new LinkedHashMap<>();
        clients.keySet().forEach(line -> copy.put(line, info.get(line)));
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Asks one engine about itself now and remembers the answer. An engine
     * that serves another line than it is configured for (e.g. with swapped
     * URLs) counts as unavailable, with a warning.
     */
    public Optional<EngineInfo> refresh(String line) {
        EngineClient client = clients.get(line);
        if (client == null) {
            return Optional.empty();
        }
        Optional<EngineInfo> fresh;
        try {
            fresh = Optional.of(client.info());
        } catch (RuntimeException e) {
            fresh = Optional.empty();
        }
        if (fresh.isPresent() && !line.equals(fresh.get().line())) {
            String served = String.valueOf(fresh.get().line());
            if (!served.equals(otherLines.put(line, served))) {
                log.warn("The validation engine configured for mzTab-M {} serves mzTab-M {}; it is not used as "
                    + "available. Check engines.mztabm[n].line and .url", line, served);
            }
            fresh = Optional.empty();
        } else {
            otherLines.remove(line);
        }
        Optional<EngineInfo> before = info.put(line, fresh);
        if (before != null && before.isPresent() && fresh.isEmpty()) {
            log.warn("The validation engine for mzTab-M {} is no longer available", line);
        } else if ((before == null || before.isEmpty()) && fresh.isPresent()) {
            log.info("The validation engine for mzTab-M {} is available (jmzTab-m {})", line,
                fresh.get().jmztabmVersion());
        }
        return fresh;
    }

    /** Asks every engine about itself, every 60 seconds; the first time right after startup, in the background. */
    @Scheduled(initialDelayString = "PT1S", fixedDelayString = "PT60S")
    public void refresh() {
        clients.keySet().forEach(this::refresh);
    }
}
