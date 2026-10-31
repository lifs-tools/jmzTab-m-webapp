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
 * The guidance for one kind of problem, with the messages it covers.
 *
 * @param entryId the catalog entry, or null when the jmzTab-m texts are used
 * @param typeCode the type code of the messages, e.g. {@code "Error-1012"}; empty if they have none
 * @param toolFixInTool the instruction of the matching tool variant, or null
 * @param cause jmzTab-m's cause text of the first message
 * @param suggestion jmzTab-m's fix text of the first message
 * @param messages indexes into the message list this was resolved for
 * @param lines the quoted lines of the first messages, in line order
 */
public record Guidance(@Nullable String entryId, String typeCode, String title, String explanation,
    String fixHere, String fixInTool, @Nullable String toolFixInTool, @Nullable String quickFix,
    boolean stopsParsing, @Nullable String cause, @Nullable String suggestion, List<Integer> messages,
    List<QuotedLine> lines) {
}
