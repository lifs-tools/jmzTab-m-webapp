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
package org.lifstools.mztab.validator.webapp.session;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.guidance.Guidance;
import org.springframework.lang.Nullable;

/**
 * A validation session: its parameters, status and result. Persisted as
 * {@code RECORD/record.json} in the session's storage directory.
 *
 * @param counts the number of messages per level; always holds all three levels
 * @param sections the sections of the parsed file, {@code null} if it was not parsed
 * @param failureReason why the validation ended {@link ValidationStatus#FAILED}
 * @param engine the engine that validated the file, e.g.
 * {@code mzTab-M 2.1 · jmzTab-m 2.1.0 (build 2026-10-30T12:00:00Z)};
 * {@code null} until an engine has validated it, and in records written
 * before validations ran through the engines
 * @param defaultVersionApplied whether the file declared no
 * {@code mzTab-version} and went to the engine of the default line
 */
public record ValidationRecord(UUID id, String fileName, ValidationStatus status, ValidationOutcome outcome,
    ValidationLevel level, int maxErrors, ProfileRef validationProfile, String mzTabVersion, Instant createdAt,
    Instant startedAt, Instant finishedAt, Instant expiresAt, Map<ValidationLevel, Integer> counts,
    List<ValidationMessage> messages, SectionsSummary sections, String failureReason, @Nullable String engine,
    boolean defaultVersionApplied, @Nullable List<Guidance> guidance) {

    public ValidationRecord {
        messages = messages == null ? List.of() : List.copyOf(messages);
        Map<ValidationLevel, Integer> all = zeroCounts();
        if (counts != null) {
            all.putAll(counts);
        }
        counts = Collections.unmodifiableMap(all);
    }

    /** A new validation, waiting to run. */
    public static ValidationRecord queued(UUID id, String fileName, ValidationLevel level, int maxErrors,
        ProfileRef profile, Instant createdAt, Instant expiresAt) {
        return new ValidationRecord(id, fileName, ValidationStatus.QUEUED, null, level, maxErrors, profile, null,
            createdAt, null, null, expiresAt, null, List.of(), null, null, null, false, null);
    }

    public ValidationRecord running(Instant now) {
        return new ValidationRecord(id, fileName, ValidationStatus.RUNNING, outcome, level, maxErrors,
            validationProfile, mzTabVersion, createdAt, now, finishedAt, expiresAt, counts, messages, sections,
            failureReason, engine, defaultVersionApplied, guidance);
    }

    /** Back in the queue: the engine was busy. */
    public ValidationRecord requeued() {
        return new ValidationRecord(id, fileName, ValidationStatus.QUEUED, outcome, level, maxErrors,
            validationProfile, mzTabVersion, createdAt, null, finishedAt, expiresAt, counts, messages, sections,
            failureReason, engine, defaultVersionApplied, guidance);
    }

    /**
     * The validation ran to the end.
     *
     * @param validatedBy the label of the engine, see {@link #engine()}
     * @param defaultApplied see {@link #defaultVersionApplied()}
     */
    public ValidationRecord finished(ValidationOutcome result, String version, List<ValidationMessage> found,
        SectionsSummary summary, @Nullable String validatedBy, boolean defaultApplied, Instant now) {
        return new ValidationRecord(id, fileName, ValidationStatus.FINISHED, result, level, maxErrors,
            validationProfile, version, createdAt, startedAt, now, expiresAt, countsOf(found), found, summary,
            null, validatedBy, defaultApplied, guidance);
    }

    /** The file was refused before parsing, with a single message that is also the failure reason. */
    public ValidationRecord rejected(String version, ValidationMessage message, Instant now) {
        return new ValidationRecord(id, fileName, ValidationStatus.FAILED, ValidationOutcome.NOT_PARSED, level,
            maxErrors, validationProfile, version, createdAt, startedAt, now, expiresAt,
            countsOf(List.of(message)), List.of(message), null, message.getMessage(), engine, defaultVersionApplied, guidance);
    }

    /** The validation could not be completed. */
    public ValidationRecord failed(String reason, Instant now) {
        return new ValidationRecord(id, fileName, ValidationStatus.FAILED, outcome, level, maxErrors,
            validationProfile, mzTabVersion, createdAt, startedAt, now, expiresAt, counts, messages, sections,
            reason, engine, defaultVersionApplied, guidance);
    }

    /** The record with its guidance; guidance is computed when a record is read, never stored. */
    public ValidationRecord withGuidance(@Nullable List<Guidance> resolved) {
        return new ValidationRecord(id, fileName, status, outcome, level, maxErrors, validationProfile, mzTabVersion,
            createdAt, startedAt, finishedAt, expiresAt, counts, messages, sections, failureReason, engine,
            defaultVersionApplied, resolved);
    }

    static Map<ValidationLevel, Integer> countsOf(List<ValidationMessage> messages) {
        Map<ValidationLevel, Integer> counts = zeroCounts();
        for (ValidationMessage m : messages) {
            if (m.getMessageType() != null) {
                counts.merge(ValidationLevel.valueOf(m.getMessageType().name()), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<ValidationLevel, Integer> zeroCounts() {
        Map<ValidationLevel, Integer> counts = new EnumMap<>(ValidationLevel.class);
        for (ValidationLevel l : ValidationLevel.values()) {
            counts.put(l, 0);
        }
        return counts;
    }
}
