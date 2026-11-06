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
package org.lifstools.mztab2.server.api.validation;

import java.util.List;
import org.springframework.lang.Nullable;

/**
 * One step of a tour, shown as a chapter excerpt and as a step of the interactive tour.
 *
 * @param chapter the docs chapter the step belongs to
 * @param lookFor an optional hint on what to look at
 * @param target the element the step highlights; none for a step about the whole file
 * @param lines the excerpt of the file, {@code [from, to]}, 1-based and inclusive
 */
public record TourStepDto(String id, String chapter, String title, String text, @Nullable String lookFor,
    @Nullable TourTargetDto target, List<Integer> lines) {

}
