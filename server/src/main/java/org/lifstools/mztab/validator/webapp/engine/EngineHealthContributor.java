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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Health of the configured engines, one indicator per line named
 * {@code engine-<line with "." replaced by "-">}: {@code UP} with what the
 * engine reports, or {@code UNAVAILABLE}. With the status order of
 * {@code application.properties} an unavailable engine does not take the
 * webapp down; a version without engine is refused per request.
 */
@Component("enginesHealthContributor")
public class EngineHealthContributor implements CompositeHealthContributor {

    public static final Status UNAVAILABLE = new Status("UNAVAILABLE");

    private final Map<String, HealthContributor> indicators = new LinkedHashMap<>();

    public EngineHealthContributor(EngineRegistry registry) {
        for (String line : registry.lines()) {
            indicators.put("engine-" + line.replace('.', '-'), (HealthIndicator) () -> health(registry, line));
        }
    }

    private static Health health(EngineRegistry registry, String line) {
        Optional<EngineInfo> info = registry.refresh(line);
        if (info.isEmpty()) {
            return Health.status(UNAVAILABLE).withDetail("line", line).build();
        }
        EngineInfo i = info.get();
        return Health.up().withDetail("line", i.line()).withDetail("mzTabVersions", i.mzTabVersions())
            .withDetail("jmztabmVersion", i.jmztabmVersion()).withDetail("build", i.build())
            .withDetail("maxUploadBytes", i.maxUploadBytes()).withDetail("maxConcurrentJobs", i.maxConcurrentJobs())
            .build();
    }

    @Override
    public HealthContributor getContributor(String name) {
        return indicators.get(name);
    }

    @Override
    public Iterator<NamedContributor<HealthContributor>> iterator() {
        return indicators.entrySet().stream()
            .map(e -> NamedContributor.of(e.getKey(), e.getValue())).iterator();
    }

    @Override
    public Stream<NamedContributor<HealthContributor>> stream() {
        return indicators.entrySet().stream().map(e -> NamedContributor.of(e.getKey(), e.getValue()));
    }
}
