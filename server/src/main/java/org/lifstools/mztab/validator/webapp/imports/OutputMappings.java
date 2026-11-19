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
package org.lifstools.mztab.validator.webapp.imports;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.lifstools.mztab.submission.mapping.Mapping;
import org.lifstools.mztab.submission.mapping.MappingLoader;
import org.lifstools.mztab.submission.mapping.MappingRequestException;
import org.springframework.stereotype.Component;

/**
 * The output mappings of the submission vocabulary on the classpath, by the mzTab-M version they write
 * ({@code target}). Output mappings are named {@code mztab-<target>}. Loaded on first use and kept.
 */
@Component
public class OutputMappings {

    private final Map<String, Optional<Mapping>> byVersion = new ConcurrentHashMap<>();

    /**
     * @param mzTabVersion the version of a file, e.g. {@code 2.0.0-M}; null is no version
     * @return the output mapping whose target is {@code mzTabVersion}; empty if there is none
     */
    public Optional<Mapping> forVersion(String mzTabVersion) {
        if (mzTabVersion == null || mzTabVersion.isBlank()) {
            return Optional.empty();
        }
        return byVersion.computeIfAbsent(mzTabVersion, v -> {
            try {
                Mapping m = MappingLoader.load("mztab-" + v);
                return m.kind() == Mapping.Kind.OUTPUT && v.equals(m.target()) ? Optional.of(m) : Optional.empty();
            } catch (MappingRequestException e) {
                // not an id, or no such mapping
                return Optional.empty();
            }
        });
    }
}
