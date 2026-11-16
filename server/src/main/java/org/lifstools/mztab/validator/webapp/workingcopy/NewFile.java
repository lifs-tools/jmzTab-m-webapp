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
package org.lifstools.mztab.validator.webapp.workingcopy;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

/**
 * What a researcher chose for a new mzTab-M file ({@link WorkingCopyService#createNew}).
 *
 * @param mzTabVersion the mzTab-M version, e.g. {@code 2.0.0-M}; its line must have an engine on this server
 * @param contentProfile the content profile ({@code mzTab-profile}) of a 2.1 file, one of {@code M}, {@code M+S},
 * {@code M+F}, {@code M+F+E} and {@code M+S+F+E}; null for 2.0, which has none
 * @param profileId the id of the catalog profile whose rules (the repository rules) the file is checked against
 * @param profileVersion the version of that profile; the profile must list {@code mzTabVersion}
 * @param mzTabId the {@code mzTab-ID}: a letter or digit, then up to 63 letters, digits, dots, underscores or
 * hyphens. The file is named {@code <mzTabId>.mzTab}.
 */
public record NewFile(String mzTabVersion, @Nullable @Schema(nullable = true) String contentProfile,
    String profileId, String profileVersion, String mzTabId) {
}
