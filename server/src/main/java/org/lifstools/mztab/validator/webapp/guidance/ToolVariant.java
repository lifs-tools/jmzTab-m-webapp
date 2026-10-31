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
package org.lifstools.mztab.validator.webapp.guidance;

import org.springframework.lang.Nullable;

/**
 * Instructions for one producing tool.
 *
 * @param software a case-insensitive regex, found in the name of an {@code MTD software[n]} value
 * @param toolVersion the tool version the instruction was written for, informational
 * @param fixInTool what to change in that tool
 */
public record ToolVariant(String software, @Nullable String toolVersion, String fixInTool) {
}
