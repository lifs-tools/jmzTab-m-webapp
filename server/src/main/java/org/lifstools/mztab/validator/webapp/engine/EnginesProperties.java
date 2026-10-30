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

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;

/**
 * The validation engines this instance talks to, one per mzTab-M version line
 * ({@code engines.mztabm[n].line} / {@code .url}; environment
 * {@code ENGINES_MZTABM_0_LINE} / {@code ENGINES_MZTABM_0_URL}).
 *
 * @param defaultLine the line a file without a declared version is validated
 * by; an engine must be configured for it
 * @param connectTimeout how long to wait for a connection to an engine; also
 * the limit for asking an engine about itself
 * @param jobTimeout how long to wait for an engine to answer a validation or
 * conversion
 * @param unavailableHintUrl where users are pointed when a version has no
 * engine on this instance; blank for none
 */
@Validated
@ConfigurationProperties("engines")
public record EnginesProperties(
    @DefaultValue("2.0") @NotBlank @Pattern(regexp = "^[0-9]+\\.[0-9]+$") String defaultLine,
    @NotEmpty @Valid List<Engine> mztabm,
    @DefaultValue("5s") @NotNull Duration connectTimeout,
    @DefaultValue("10m") @NotNull Duration jobTimeout,
    @Nullable String unavailableHintUrl) {

    /**
     * One engine.
     *
     * @param line the version line, {@code MAJOR.MINOR}
     * @param url the base URL of the engine, http or https
     */
    public record Engine(@NotBlank @Pattern(regexp = "^[0-9]+\\.[0-9]+$") String line, @NotNull URI url) {

        @AssertTrue(message = "url must be an absolute http or https URL")
        boolean isHttpUrl() {
            return url == null || (url.getScheme() != null && url.getHost() != null
                && (url.getScheme().equalsIgnoreCase("http") || url.getScheme().equalsIgnoreCase("https")));
        }
    }

    @AssertTrue(message = "each line may have only one engine: lines must be unique")
    boolean isLinesUnique() {
        if (mztabm == null) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        return mztabm.stream().allMatch(e -> e == null || seen.add(e.line()));
    }
}
