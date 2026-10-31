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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.springframework.lang.Nullable;

/** The user-facing texts about version lines and engines. */
public final class EngineMessages {

    private EngineMessages() {
    }

    /**
     * The one message for an mzTab 1.x file, which is not validated any more: {@code This file declares
     * mzTab-version 1.0. mzTab 1.0 is no longer supported; the validator checks mzTab-M <lines> files.}
     *
     * @param lines the configured lines
     */
    public static String legacy(Set<String> lines) {
        return "This file declares mzTab-version 1.0. mzTab 1.0 is no longer supported; the validator checks "
            + "mzTab-M " + joined(lines) + " files.";
    }

    /**
     * Why a file goes to no engine: {@link #legacy} for an mzTab 1.x file,
     * {@link #unsupported} for a version without an engine on this instance.
     *
     * @return empty if the file goes to an engine
     */
    public static Optional<String> rejection(EngineRegistry.RouteDecision decision, @Nullable String hintUrl) {
        return switch (decision) {
            case EngineRegistry.RouteDecision.Legacy legacy -> Optional.of(legacy(legacy.supported()));
            case EngineRegistry.RouteDecision.Unsupported unsupported -> Optional.of(unsupported(unsupported, hintUrl));
            case EngineRegistry.RouteDecision.Routed routed -> Optional.empty();
        };
    }

    /** {@code No default validation rules for mzTab-M <line>; semantic validation skipped.} */
    public static String noDefaultProfile(String line) {
        return "No default validation rules for mzTab-M " + line + "; semantic validation skipped.";
    }

    /**
     * {@code This file declares mzTab-version <declared>. This validator supports mzTab-M <lines>.} and, with a
     * hint URL, {@code Another validator may support mzTab-M <line>: <url>}.
     */
    public static String unsupported(EngineRegistry.RouteDecision.Unsupported decision, @Nullable String hintUrl) {
        StringBuilder text = new StringBuilder("This file declares mzTab-version ").append(decision.declared())
            .append(". This validator supports mzTab-M ").append(joined(decision.supported())).append('.');
        Optional<String> line = MzTabVersions.lineOf(decision.declared());
        if (hintUrl != null && !hintUrl.isBlank() && line.isPresent()) {
            text.append(" Another validator may support mzTab-M ").append(line.get()).append(": ").append(hintUrl.strip());
        }
        return text.toString();
    }

    /** {@code The mzTab-M <line> validator is temporarily unavailable. Please try again later.} */
    public static String unavailable(String line) {
        return "The mzTab-M " + line + " validator is temporarily unavailable. Please try again later.";
    }

    /**
     * {@code The validation profile <id> <version> applies to mzTab-M <lines>, but the file is validated as
     * mzTab-M <line>.}
     */
    public static String profileOfOtherLine(String id, String version, Set<String> profileLines, String line) {
        return "The validation profile " + id + " " + version + " applies to mzTab-M " + joined(profileLines)
            + ", but the file is validated as mzTab-M " + line + ".";
    }

    /** Lines joined with {@code " and "}, e.g. {@code 2.0 and 2.1}. */
    static String joined(Set<String> lines) {
        return String.join(" and ", List.copyOf(lines));
    }
}
