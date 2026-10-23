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
import org.lifstools.mztab.validator.webapp.domain.ValidationLevel;
import org.lifstools.mztab2.model.ValidationMessage;

/**
 * A validation session: its parameters, status and result. Persisted as
 * {@code RECORD/record.json} in the session's storage directory.
 *
 * @param counts the number of messages per level; always holds all three levels
 * @param sections the sections of the parsed file, {@code null} if it was not parsed
 * @param failureReason why the validation ended {@link ValidationStatus#FAILED}
 */
public record ValidationRecord(UUID id, String fileName, ValidationStatus status, ValidationOutcome outcome,
    ValidationLevel level, int maxErrors, ProfileRef validationProfile, String mzTabVersion, Instant createdAt,
    Instant startedAt, Instant finishedAt, Instant expiresAt, Map<ValidationLevel, Integer> counts,
    List<ValidationMessage> messages, SectionsSummary sections, String failureReason) {

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
            createdAt, null, null, expiresAt, null, List.of(), null, null);
    }

    public ValidationRecord running(Instant now) {
        return new ValidationRecord(id, fileName, ValidationStatus.RUNNING, outcome, level, maxErrors,
            validationProfile, mzTabVersion, createdAt, now, finishedAt, expiresAt, counts, messages, sections,
            failureReason);
    }

    /** The validation ran to the end. */
    public ValidationRecord finished(ValidationOutcome result, String version, List<ValidationMessage> found,
        SectionsSummary summary, Instant now) {
        return new ValidationRecord(id, fileName, ValidationStatus.FINISHED, result, level, maxErrors,
            validationProfile, version, createdAt, startedAt, now, expiresAt, countsOf(found), found, summary,
            null);
    }

    /** The file was refused before parsing, with a single message that is also the failure reason. */
    public ValidationRecord rejected(String version, ValidationMessage message, Instant now) {
        return new ValidationRecord(id, fileName, ValidationStatus.FAILED, ValidationOutcome.NOT_PARSED, level,
            maxErrors, validationProfile, version, createdAt, startedAt, now, expiresAt,
            countsOf(List.of(message)), List.of(message), null, message.getMessage());
    }

    /** The validation could not be completed. */
    public ValidationRecord failed(String reason, Instant now) {
        return new ValidationRecord(id, fileName, ValidationStatus.FAILED, outcome, level, maxErrors,
            validationProfile, mzTabVersion, createdAt, startedAt, now, expiresAt, counts, messages, sections,
            reason);
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
