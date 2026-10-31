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
import java.util.stream.Collectors;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;

/** The problems of a validation and how to resolve them, as Markdown to hand to whoever fixes the file. */
public final class ResolutionReport {

    private ResolutionReport() {
    }

    public static String markdown(ValidationRecord record, List<Guidance> guidance) {
        StringBuilder md = new StringBuilder("# Validation problems in ").append(record.fileName()).append("\n\n");
        if (guidance.isEmpty()) {
            return md.append("No problems were found.\n").toString();
        }
        for (Guidance g : guidance) {
            md.append("## ").append(g.title()).append(" (").append(g.messages().size()).append(")\n\n");
            md.append(g.explanation()).append("\n\n");
            if (g.cause() != null && !g.cause().isBlank()) {
                md.append("Cause: ").append(g.cause()).append("\n\n");
            }
            md.append("**Fix in the tool that wrote the file:** ")
                .append(g.toolFixInTool() != null ? g.toolFixInTool() : g.fixInTool()).append("\n\n");
            if (!g.lines().isEmpty()) {
                md.append("Lines: ").append(g.lines().stream().map(l -> Long.toString(l.lineNumber()))
                    .collect(Collectors.joining(", "))).append("\n\n");
                md.append(fence(g.lines())).append("\n");
            }
        }
        return md.toString();
    }

    private static String fence(List<QuotedLine> lines) {
        StringBuilder sb = new StringBuilder("```\n");
        for (QuotedLine l : lines) {
            sb.append(l.text().replace("```", "'''")).append(l.truncated() ? " ..." : "").append('\n');
        }
        return sb.append("```\n").toString();
    }
}
