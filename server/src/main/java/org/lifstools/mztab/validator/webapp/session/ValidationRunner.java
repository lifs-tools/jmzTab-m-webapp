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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.lifstools.mztab.validator.engine.api.ValidationEvent;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.engine.DeclaredVersions;
import org.lifstools.mztab.validator.webapp.engine.EngineBusyException;
import org.lifstools.mztab.validator.webapp.engine.EngineMessages;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry.RouteDecision;
import org.lifstools.mztab.validator.webapp.engine.EngineRequestException;
import org.lifstools.mztab.validator.webapp.engine.EngineResultReader;
import org.lifstools.mztab.validator.webapp.engine.EngineUnavailableException;
import org.lifstools.mztab.validator.webapp.engine.EnginesProperties;
import org.lifstools.mztab.validator.webapp.service.AnalyticsTracker;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs a queued validation through the engine of the file's version line:
 * the declared version decides the engine, the engine validates (with the
 * mapping of the validation's profile) and streams its result, which is
 * stored as the validation's record and sections. A busy engine puts the
 * validation back in the queue and it is retried with a growing delay until
 * {@code engines.job-timeout} after it was created.
 */
@Slf4j
@Component
public class ValidationRunner {

    static final String MZTAB_1_0 = EngineMessages.LEGACY;
    static final String UNEXPECTED_ERROR = "The validation could not be completed because of an unexpected "
        + "server error. Please try again later.";
    /** The first delay after a busy answer; it doubles with each further one. */
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final ValidationRecordStore records;
    private final StorageService storage;
    private final ValidationProfileCatalog catalog;
    private final EngineRegistry registry;
    private final EngineResultReader reader;
    private final EnginesProperties engines;
    private final AnalyticsTracker tracker;
    private final TaskScheduler scheduler;
    private final Executor executor;
    private final Clock clock;
    /** The last delay of each validation waiting for a busy engine. */
    private final Map<UUID, Duration> backoffs = new ConcurrentHashMap<>();

    @Autowired
    public ValidationRunner(ValidationRecordStore records, StorageService storage, ValidationProfileCatalog catalog,
        EngineRegistry registry, EngineResultReader reader, EnginesProperties engines, AnalyticsTracker tracker,
        TaskScheduler scheduler, @Qualifier("toolThreadPoolTaskExecutor") Executor executor) {
        this(records, storage, catalog, registry, reader, engines, tracker, scheduler, executor, Clock.systemUTC());
    }

    ValidationRunner(ValidationRecordStore records, StorageService storage, ValidationProfileCatalog catalog,
        EngineRegistry registry, EngineResultReader reader, EnginesProperties engines, AnalyticsTracker tracker,
        TaskScheduler scheduler, Executor executor, Clock clock) {
        this.records = records;
        this.storage = storage;
        this.catalog = catalog;
        this.registry = registry;
        this.reader = reader;
        this.engines = engines;
        this.tracker = tracker;
        this.scheduler = scheduler;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Runs the validation if it is still {@link ValidationStatus#QUEUED};
     * concurrent or repeated calls for the same id run it once.
     */
    @Async("toolThreadPoolTaskExecutor")
    public void run(UUID id) {
        Optional<ValidationRecord> started = records.update(id,
            r -> r.status() == ValidationStatus.QUEUED ? r.running(clock.instant()) : null);
        if (started.isEmpty()) {
            return;
        }
        ValidationRecord running = started.get();
        boolean retry = backoffs.containsKey(id);
        if (!retry) {
            track(() -> tracker.started(id, "validation", "init"));
        }
        Step step = null;
        try {
            step = execute(running);
        } catch (Exception e) {
            log.error("Validation {} failed", id, e);
            step = Step.done(running.failed(UNEXPECTED_ERROR, clock.instant()));
        } finally {
            Step last = step != null ? step : Step.done(running.failed(UNEXPECTED_ERROR, clock.instant()));
            // a session deleted while running stays deleted
            Optional<ValidationRecord> stored = records.update(id,
                current -> current.status() == ValidationStatus.RUNNING ? last.record() : null);
            if (stored.isEmpty() && records.read(id).isEmpty()) {
                // the result reader runs outside the session's lock and may have recreated the files
                storage.deleteAll(id);
                backoffs.remove(id);
            } else if (stored.isPresent() && last.retryIn() != null) {
                retry(id, last.retryIn());
            } else {
                backoffs.remove(id);
                String type = last.record().status() == ValidationStatus.FINISHED ? "success" : "fail";
                track(() -> tracker.stopped(id, "validation", type));
            }
        }
    }

    /**
     * What one attempt produced.
     *
     * @param retryIn when to try again, for a validation back in the queue
     */
    private record Step(ValidationRecord record, @Nullable Duration retryIn) {

        static Step done(ValidationRecord record) {
            return new Step(record, null);
        }
    }

    private void retry(UUID id, Duration delay) {
        try {
            scheduler.schedule(() -> executor.execute(() -> run(id)), clock.instant().plus(delay));
            log.info("Validation {}: the engine is busy; trying again in {} s", id, delay.toSeconds());
        } catch (RuntimeException e) {
            log.error("Validation {}: could not schedule another attempt", id, e);
            backoffs.remove(id);
            records.update(id, r -> r.status() == ValidationStatus.QUEUED
                ? r.failed(UNEXPECTED_ERROR, clock.instant()) : null);
            track(() -> tracker.stopped(id, "validation", "fail"));
        }
    }

    /** Tracking is best effort and never fails a validation. */
    private static void track(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("Could not record a tracking event", e);
        }
    }

    private Step execute(ValidationRecord record) throws IOException {
        Path upload = null;
        Path mapping = null;
        try {
            upload = toTempFile(storage.read(record.id(), SLOT.MZTABFILE, ValidationSessionService.UPLOAD)
                .orElseThrow(() -> new IllegalStateException("The upload of validation " + record.id()
                + " is missing")), ".mztab");
            Optional<String> declared = declaredVersion(upload);
            RouteDecision decision = registry.route(declared);
            Optional<String> rejection = EngineMessages.rejection(decision, engines.unavailableHintUrl());
            if (rejection.isPresent()) {
                return Step.done(record.rejected(declared.orElse(null), ValidationMessage.error(rejection.get()),
                    clock.instant()));
            }
            RouteDecision.Routed routed = (RouteDecision.Routed) decision;
            mapping = mappingFile(record);
            try {
                EngineResultReader.EngineResult result = routed.engine().validate(upload, mapping, record.level(),
                    record.maxErrors(), in -> reader.read(record.id(), in));
                backoffs.remove(record.id());
                return Step.done(finished(record, declared, routed, result));
            } catch (EngineBusyException busy) {
                return busy(record, routed.line(), busy);
            } catch (EngineUnavailableException e) {
                log.warn("Validation {}: the engine for mzTab-M {} is unavailable: {}", record.id(), routed.line(),
                    e.getCause() == null ? e.getMessage() : e.getCause().toString());
                return Step.done(record.failed(EngineMessages.unavailable(routed.line()), clock.instant()));
            } catch (EngineRequestException e) {
                // the engine refused the file, e.g. as too large or as of another line
                log.warn("Validation {}: the engine for mzTab-M {} refused the file: {} {}", record.id(),
                    routed.line(), e.error().code(), e.error().message());
                return Step.done(record.rejected(declared.orElse(null), ValidationMessage.error(e.error().message()),
                    clock.instant()));
            }
        } finally {
            deleteQuietly(upload);
            deleteQuietly(mapping);
        }
    }

    private ValidationRecord finished(ValidationRecord record, Optional<String> declared,
        RouteDecision.Routed routed, EngineResultReader.EngineResult result) {
        ValidationEvent.Outcome outcome = result.outcome();
        String version = outcome.mzTabVersion();
        if (declared.isPresent() && !declared.get().equals(version)) {
            // the engine's parser has the last word
            log.info("Validation {}: the engine for mzTab-M {} reports mzTab-version {}, the file declares {}",
                record.id(), routed.line(), version, declared.get());
        }
        List<ValidationMessage> messages = outcome.messages() == null ? List.of() : outcome.messages();
        return record.finished(outcomeOf(outcome.parsed(), messages), version, messages, result.sections(),
            engineLabel(routed.line()), routed.defaultApplied(), clock.instant());
    }

    /** Back to the queue, or FAILED if the validation has waited long enough. */
    private Step busy(ValidationRecord record, String line, EngineBusyException busy) {
        Instant now = clock.instant();
        Instant created = record.createdAt() == null ? now : record.createdAt();
        if (!now.isBefore(created.plus(engines.jobTimeout()))) {
            log.warn("Validation {}: the engine for mzTab-M {} was busy for longer than {}", record.id(), line,
                engines.jobTimeout());
            return Step.done(record.failed(EngineMessages.unavailable(line), now));
        }
        Duration backoff = backoffs.merge(record.id(), INITIAL_BACKOFF,
            (previous, initial) -> min(previous.multipliedBy(2), MAX_BACKOFF));
        Duration delay = busy.retryAfter().compareTo(backoff) > 0 ? busy.retryAfter() : backoff;
        return new Step(record.requeued(), delay);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /**
     * E.g. {@code mzTab-M 2.1 · jmzTab-m 2.1.0 (build 2026-10-30T12:00:00Z)};
     * the line alone if the engine did not report about itself.
     */
    private String engineLabel(String line) {
        Optional<EngineInfo> info = registry.lastKnownInfo().getOrDefault(line, Optional.empty());
        if (info.isEmpty()) {
            info = registry.refresh(line);
        }
        return info.map(i -> "mzTab-M " + i.line() + " · jmzTab-m " + i.jmztabmVersion() + " (build " + i.build()
            + ")").orElse("mzTab-M " + line);
    }

    /**
     * The declared {@code mzTab-version} of a tab separated or JSON file. A
     * file that is not UTF-8 text or not well formed JSON declares none here:
     * it goes to the default line, whose parser reports it.
     */
    static Optional<String> declaredVersion(Path file) throws IOException {
        return DeclaredVersions.of(file);
    }

    /** The mapping as a local file, or {@code null} for structural validation only. */
    @Nullable
    private Path mappingFile(ValidationRecord record) throws IOException {
        ProfileRef profile = record.validationProfile();
        return switch (profile.kind()) {
            case NONE -> null;
            case CATALOG -> {
                ValidationProfile p = catalog.find(profile.id(), profile.version())
                    .orElseThrow(() -> new IllegalStateException("Validation profile " + profile.id() + " "
                    + profile.version() + " is no longer in the catalog"));
                yield toTempFile(catalog.mappingUrl(p).openStream(), ".xml");
            }
            case CUSTOM -> toTempFile(storage.read(record.id(), SLOT.MAPPINGFILE,
                ValidationSessionService.CUSTOM_MAPPING).orElseThrow(() -> new IllegalStateException(
                "The custom mapping of validation " + record.id() + " is missing")), ".xml");
        };
    }

    static ValidationOutcome outcomeOf(boolean parsed, List<ValidationMessage> messages) {
        if (!parsed) {
            return ValidationOutcome.NOT_PARSED;
        }
        if (messages.stream().anyMatch(m -> m.getMessageType() == ValidationMessage.MessageType.ERROR)) {
            return ValidationOutcome.FAILED;
        }
        return messages.isEmpty() ? ValidationOutcome.PASSED : ValidationOutcome.PASSED_WITH_NOTES;
    }

    /**
     * The engine client streams files from paths; session data stays behind
     * {@link StorageService}, so the runner works on a temporary copy.
     */
    static Path toTempFile(InputStream content, String suffix) throws IOException {
        try (InputStream in = content) {
            Path file = Files.createTempFile("mztab-validation-", suffix);
            try {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                return file;
            } catch (IOException | RuntimeException e) {
                deleteQuietly(file);
                throw e;
            }
        }
    }

    static void deleteQuietly(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temporary file {}", file, e);
        }
    }
}
