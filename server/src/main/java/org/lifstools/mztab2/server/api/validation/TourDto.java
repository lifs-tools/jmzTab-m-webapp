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
 * The steps through a bundled example file.
 *
 * @param exampleId the example the tour belongs to, from the example entry
 * @param counterpart the example of the same tour on the other mzTab-M version, if there is one
 */
public record TourDto(String exampleId, String title, @Nullable String counterpart, List<TourStepDto> steps) {

}
