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

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.lifstools.mztab.validator.webapp.session.ProfileRef;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;
import org.springframework.lang.Nullable;

/** The problems of a validation and how to resolve them, as Markdown to hand to whoever fixes the file. */
public final class ResolutionReport {

    private ResolutionReport() {
    }

    /**
     * The check of a working copy that a report describes.
     *
     * @param checkedRevision the revision of the run that produced the messages
     * @param currentRevision the copy's revision now
     * @param checkedAt when that run ended; null if not known
     * @param semantic whether the run checked CV rules too
     * @param profile the session's profile, which a semantic run applies; null counts as none
     * @param running whether a new check is running now
     * @param readUpTo if the engine did not read the whole text: the line at which it stopped
     * @param failedRevision if the latest check gave no result: its revision
     * @param failureReason why the latest check gave no result
     */
    public record CopyCheck(String fileName, @Nullable String version, long checkedRevision, long currentRevision,
        @Nullable Instant checkedAt, boolean semantic, @Nullable ProfileRef profile, boolean running,
        @Nullable Long readUpTo, @Nullable Long failedRevision, @Nullable String failureReason) {
    }

    /** The problems of a working copy's last check, with the lines of the copy as it was checked. */
    public static String markdown(CopyCheck check, List<Guidance> guidance) {
        StringBuilder md = new StringBuilder("# Validation problems in ").append(oneLine(check.fileName()))
            .append(" (corrected copy)\n\n");
        md.append("Revision ").append(check.checkedRevision()).append(" of the corrected copy");
        if (check.version() != null && !check.version().isBlank()) {
            md.append(", mzTab-M version ").append(oneLine(check.version()));
        }
        md.append(". Checked ");
        if (check.checkedAt() != null) {
            md.append(check.checkedAt()).append(", ");
        }
        md.append(check.semantic() ? "with " + rules(check.profile()) : "structure only").append(".\n\n");
        if (check.checkedRevision() != check.currentRevision()) {
            md.append("This is the check of revision ").append(check.checkedRevision()).append("; the copy is at revision ")
                .append(check.currentRevision()).append(". The line numbers are those of revision ")
                .append(check.checkedRevision()).append(".\n\n");
        }
        if (check.failedRevision() != null) {
            md.append("The latest check, of revision ").append(check.failedRevision()).append(", gave no result")
                .append(check.failureReason() == null ? "" : ": " + oneLine(check.failureReason())).append("\n\n");
        }
        if (check.running()) {
            md.append("A new check is running.\n\n");
        }
        if (check.readUpTo() != null) {
            md.append("jmzTab-m stopped reading at line ").append(check.readUpTo())
                .append("; problems further down are not reported yet.\n\n");
        }
        return problems(md, guidance);
    }

    private static String rules(@Nullable ProfileRef profile) {
        if (profile != null && profile.kind() == ProfileRef.Kind.CATALOG) {
            return "CV rules of the profile " + oneLine(profile.id()) + " " + oneLine(profile.version());
        }
        if (profile != null && profile.kind() == ProfileRef.Kind.CUSTOM) {
            return "CV rules of the uploaded mapping file";
        }
        return "CV rules of the default profile of the file's version";
    }

    public static String markdown(ValidationRecord record, List<Guidance> guidance) {
        return problems(new StringBuilder("# Validation problems in ").append(oneLine(record.fileName())).append("\n\n"),
            guidance);
    }

    private static String problems(StringBuilder md, List<Guidance> guidance) {
        if (guidance.isEmpty()) {
            return md.append("No problems were found.\n").toString();
        }
        for (Guidance g : guidance) {
            md.append("## ").append(oneLine(g.title())).append(" (").append(g.messages().size()).append(")\n\n");
            md.append(oneLine(g.explanation())).append("\n\n");
            if (g.cause() != null && !g.cause().isBlank()) {
                md.append("Cause: ").append(oneLine(g.cause())).append("\n\n");
            }
            String toolFix = g.toolFixInTool() != null ? g.toolFixInTool() : g.fixInTool();
            if (toolFix != null && !toolFix.isBlank()) {
                md.append("**Fix in the tool that wrote the file")
                    .append(g.toolFixInTool() != null && g.toolName() != null ? " (" + oneLine(g.toolName()) + ")" : "")
                    .append(":** ").append(oneLine(toolFix)).append("\n\n");
            }
            if (!g.lines().isEmpty()) {
                md.append("Lines: ").append(g.lines().stream().map(l -> Long.toString(l.lineNumber()))
                    .collect(Collectors.joining(", "))).append("\n\n");
                md.append(fence(g.lines())).append("\n");
            }
        }
        return md.toString();
    }

    /** Text outside the fenced block stays on its line: the file name and the catalog text may hold line breaks. */
    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n]+", " ");
    }

    private static String fence(List<QuotedLine> lines) {
        StringBuilder sb = new StringBuilder("```\n");
        for (QuotedLine l : lines) {
            sb.append(l.text().replace("```", "'''")).append(l.truncated() ? " ..." : "").append('\n');
        }
        return sb.append("```\n").toString();
    }
}
