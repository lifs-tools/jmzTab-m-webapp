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

import java.util.List;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.guidance.Guidance;
import org.springframework.lang.Nullable;

/**
 * The state of a working copy's validation. A run validates the text of
 * exactly one revision, with the engine of the version that text declares.
 * While a run is {@link Status#RUNNING}, the lists, {@code parsed} and
 * {@code readUpTo} are those of the run before (empty if there was none), so
 * a client can keep showing them.
 *
 * @param revision the revision whose text is (being) validated
 * @param stale whether the copy has changed since that revision
 * @param parsed whether the engine read the whole text
 * @param readUpTo if not parsed: the first line at which the engine stopped
 * reading (the smallest line of a message whose guidance says it stops the
 * parse); otherwise null
 * @param semantic whether the run checks the CV rules of a profile too
 * @param messages the messages of the run, each NEW or OPEN compared with the
 * last run of the same kind (structural or semantic) before it; the first run
 * compares with the session's own validation of that kind
 * @param fixed the messages of that run before that this run no longer reports
 * @param guidance the guidance for {@code messages}; its message indexes refer
 * to that list
 * @param failureReason why the run ended {@link Status#FAILED}
 */
public record WorkingCopyValidation(Status status, long revision, boolean stale, boolean parsed,
    @Nullable Long readUpTo, boolean semantic, List<TrackedMessage> messages, List<ValidationMessage> fixed,
    List<Guidance> guidance, @Nullable String failureReason) {

    public enum Status {
        /** Waiting for or being validated by the engine. */
        RUNNING,
        /** The engine (or the routing) gave a result. */
        DONE,
        /** No result: the engine could not be reached, gave up or the run broke. */
        FAILED
    }

    public WorkingCopyValidation {
        messages = messages == null ? List.of() : List.copyOf(messages);
        fixed = fixed == null ? List.of() : List.copyOf(fixed);
        guidance = guidance == null ? List.of() : List.copyOf(guidance);
    }

    /** A run without a result. */
    static WorkingCopyValidation failed(long revision, boolean semantic, String reason) {
        return new WorkingCopyValidation(Status.FAILED, revision, false, false, null, semantic, List.of(), List.of(),
            List.of(), reason);
    }

    /** A run of {@code revision} that has started; the lists of this (ended) one stay visible. */
    WorkingCopyValidation running(long revision, boolean semantic) {
        return new WorkingCopyValidation(Status.RUNNING, revision, stale, parsed, readUpTo, semantic, messages, fixed,
            guidance, null);
    }

    /** The first run of a copy, before any result. */
    static WorkingCopyValidation firstRunning(long revision, boolean semantic) {
        return new WorkingCopyValidation(Status.RUNNING, revision, false, false, null, semantic, List.of(), List.of(),
            List.of(), null);
    }

    WorkingCopyValidation withStale(boolean stale) {
        return new WorkingCopyValidation(status, revision, stale, parsed, readUpTo, semantic, messages, fixed,
            guidance, failureReason);
    }

    /** Whether the run ended with a parsed file and without an error. */
    boolean valid() {
        return status == Status.DONE && parsed && messages.stream()
            .noneMatch(t -> t.message().getMessageType() == ValidationMessage.MessageType.ERROR);
    }
}
