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

import java.util.List;
import org.springframework.lang.Nullable;

/**
 * One curated piece of guidance for a validation problem.
 *
 * @param id a unique, stable id
 * @param codes type codes like {@code "Error-1012"}; a message matches by the number
 * @param messagePattern a Java regex that must be found in the message text, or null
 * @param lines the mzTab-M lines ({@code "2.0"}, {@code "2.1"}) the entry applies to; null means all
 * @param title the problem in plain words, at most 60 characters
 * @param explanation why it is a problem
 * @param fixHere what to do in the editor
 * @param fixInTool what an exporter developer should write instead
 * @param quickFix the id of a quick fix, or null
 * @param stopsParsing whether the engine stops reading the file at this problem
 * @param tools instructions for particular producing tools
 */
public record GuidanceEntry(String id, List<String> codes, @Nullable String messagePattern,
    @Nullable List<String> lines, String title, String explanation, String fixHere, String fixInTool,
    @Nullable String quickFix, boolean stopsParsing, List<ToolVariant> tools) {

    public GuidanceEntry {
        codes = codes == null ? List.of() : List.copyOf(codes);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
