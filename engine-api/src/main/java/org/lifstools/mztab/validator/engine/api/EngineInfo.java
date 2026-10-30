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
package org.lifstools.mztab.validator.engine.api;

import java.util.List;

/**
 * What an engine reports about itself.
 *
 * @param line the version line it validates, e.g. {@code 2.0}
 * @param mzTabVersions the declared versions it accepts
 * @param jmztabmVersion the jmzTab-m version it embeds
 * @param build its build identifier
 * @param maxUploadBytes the largest accepted upload
 * @param maxConcurrentJobs how many jobs it runs at once
 */
public record EngineInfo(String line, List<String> mzTabVersions, String jmztabmVersion, String build,
    long maxUploadBytes, int maxConcurrentJobs) {
}
