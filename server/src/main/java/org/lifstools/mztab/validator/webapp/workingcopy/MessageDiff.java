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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.lifstools.mztab.validator.engine.api.ValidationLocation;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.guidance.GuidanceCatalog;

/**
 * Compares the messages of two validation runs. A message is the same problem
 * as one of the run before if it has the same type code (e.g.
 * {@code Error-1012}) and the same place, taken from the first of these that
 * the message has:
 * <ol>
 * <li>its metadata key;</li>
 * <li>its table cell: section, row index and column;</li>
 * <li>its metadata group (a message about a whole group, e.g. Info-2057 for {@code database});</li>
 * <li>its line number;</li>
 * <li>otherwise its text.</li>
 * </ol>
 * Repeated messages match one to one, in order.
 */
public final class MessageDiff {

    /**
     * @param current the messages of the new run, in its order, each NEW or OPEN
     * @param fixed the messages of the run before that the new run does not report, in their order
     */
    public record Result(List<TrackedMessage> current, List<ValidationMessage> fixed) {
    }

    private MessageDiff() {
    }

    public static Result compare(List<ValidationMessage> previous, List<ValidationMessage> current) {
        Map<List<Object>, Deque<Integer>> unmatched = new HashMap<>();
        for (int i = 0; i < previous.size(); i++) {
            unmatched.computeIfAbsent(key(previous.get(i)), k -> new ArrayDeque<>()).add(i);
        }
        boolean[] matched = new boolean[previous.size()];
        List<TrackedMessage> tracked = new ArrayList<>(current.size());
        for (ValidationMessage m : current) {
            Deque<Integer> same = unmatched.get(key(m));
            if (same != null && !same.isEmpty()) {
                matched[same.poll()] = true;
                tracked.add(new TrackedMessage(m, TrackedMessage.State.OPEN));
            } else {
                tracked.add(new TrackedMessage(m, TrackedMessage.State.NEW));
            }
        }
        List<ValidationMessage> fixed = new ArrayList<>();
        for (int i = 0; i < previous.size(); i++) {
            if (!matched[i]) {
                fixed.add(previous.get(i));
            }
        }
        return new Result(List.copyOf(tracked), List.copyOf(fixed));
    }

    /**
     * Whether a message is of the CV-rule (semantic) check. jmzTab-m gives every message of that check the
     * category {@code cross_check} (the error types 3000 to 3016), and the engines give their own notes about the
     * check (skipped, messages omitted) that category too; the structural check never does.
     */
    public static boolean isCvRule(ValidationMessage m) {
        return m.getCategory() == ValidationMessage.Category.CROSS_CHECK;
    }

    /**
     * Whether a message says that the CV rules were not checked although a run asked for them (the engine's
     * {@link ValidationMessage#CV_NOT_CHECKED}: skipped for a file without a model, or the check broke). It is no
     * result of the rules: it is neither a CV message to keep nor a structural one to compare.
     */
    public static boolean isCvNotChecked(ValidationMessage m) {
        return ValidationMessage.CV_NOT_CHECKED.equals(m.getCode());
    }

    /**
     * Compares a run with the messages shown before it, each kind with its own: the structural messages with the
     * structural ones before, and, for a semantic run, its CV messages with the CV messages before. A structural
     * run does not check the CV rules: the CV messages before it stay, OPEN, after its own, and none of them is
     * fixed.
     *
     * @param structuralBefore the structural messages of the latest run, of either kind
     * @param cvBefore the CV messages of the last CV check (or of the session's validation)
     * @param semantic whether the run checked the CV rules
     */
    public static Result compareRun(List<ValidationMessage> structuralBefore, List<ValidationMessage> cvBefore,
        List<ValidationMessage> current, boolean semantic) {
        if (!semantic) {
            Result structural = compare(structuralBefore, current);
            List<TrackedMessage> shown = new ArrayList<>(structural.current());
            cvBefore.forEach(m -> shown.add(new TrackedMessage(m, TrackedMessage.State.OPEN)));
            return new Result(List.copyOf(shown), structural.fixed());
        }
        Result structural = compare(structuralBefore, current.stream().filter(m -> !isCvRule(m)).toList());
        Result cv = compare(cvBefore, current.stream().filter(MessageDiff::isCvRule).toList());
        // in the run's own order
        List<TrackedMessage> shown = new ArrayList<>(current.size());
        int s = 0;
        int c = 0;
        for (ValidationMessage m : current) {
            shown.add(isCvRule(m) ? cv.current().get(c++) : structural.current().get(s++));
        }
        List<ValidationMessage> fixed = new ArrayList<>(structural.fixed());
        fixed.addAll(cv.fixed());
        return new Result(List.copyOf(shown), List.copyOf(fixed));
    }

    /** The type code and the first place the message has. */
    static List<Object> key(ValidationMessage m) {
        String code = GuidanceCatalog.typeCodeOf(m);
        ValidationLocation l = m.getLocation();
        if (l != null && l.getMetadataKey() != null) {
            return List.of(code, "key", l.getMetadataKey());
        }
        if (l != null && l.getSection() != null && (l.getRowIndex() != null || l.getColumn() != null)) {
            return Arrays.asList(code, "cell", l.getSection(), l.getRowIndex(), l.getColumn());
        }
        if (l != null && l.getMetadataGroup() != null) {
            return List.of(code, "group", l.getMetadataGroup());
        }
        if (m.getLineNumber() != null && m.getLineNumber() > 0) {
            return List.of(code, "line", m.getLineNumber());
        }
        return Arrays.asList(code, "text", m.getMessage());
    }
}
