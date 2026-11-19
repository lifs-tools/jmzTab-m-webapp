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

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The starting text of a new mzTab-M file.
 *
 * @param mzTabVersion the mzTab-M version, e.g. {@code 2.0.0-M}; the engine must serve it
 * @param contentProfile the content profile ({@code M}, {@code M+S}, {@code M+F}, {@code M+F+E}, {@code M+S+F+E});
 *     required for 2.1, refused for 2.0
 * @param mzTabId the {@code mzTab-ID}, matching {@code ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$}
 */
public record SkeletonRequest(String mzTabVersion, @Schema(nullable = true) String contentProfile,
    String mzTabId) {
}
