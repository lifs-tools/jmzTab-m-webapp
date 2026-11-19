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

import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.META_BASELINE_SEMANTIC;
import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.META_BASELINE_STRUCTURAL;
import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.META_CV_REVISION;
import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.META_GENERATION;
import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.META_LAST;
import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.META_LAST_DONE;
import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.META_LAST_DONE_AT;
import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.META_VERSION;
import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService.toTempFile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.engine.api.ValidationEvent;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.engine.DeclaredVersions;
import org.lifstools.mztab.validator.webapp.engine.EngineBusyException;
import org.lifstools.mztab.validator.webapp.engine.EngineMessages;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry.RouteDecision;
import org.lifstools.mztab.validator.webapp.engine.EngineRejection;
import org.lifstools.mztab.validator.webapp.engine.EngineRequestException;
import org.lifstools.mztab.validator.webapp.engine.EngineResultReader;
import org.lifstools.mztab.validator.webapp.engine.EngineUnavailableException;
import org.lifstools.mztab.validator.webapp.engine.EnginesProperties;
import org.lifstools.mztab.validator.webapp.guidance.Guidance;
import org.lifstools.mztab.validator.webapp.guidance.GuidanceResolver;
import org.lifstools.mztab.validator.webapp.guidance.UploadLines;
import org.lifstools.mztab.validator.webapp.service.StorageService;
import org.lifstools.mztab.validator.webapp.service.StorageService.SLOT;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousEngineService;
import org.lifstools.mztab.validator.webapp.session.ProfileRef;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation.Status;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;

/**
 * The validation runs of the working copies, for {@link WorkingCopyService}, which owns one and delegates to it:
 * a run's request ({@link #requestValidation}), its loop on {@code workingCopyRunExecutor}, the retries of a
 * busy engine on the scheduler, and the storing of an ended run in the copy's meta. One run per copy at a time; the
 * running runs are held in memory only, guarded by {@link #runs}. The copies themselves (opening one, its settings)
 * are the service's, reached through {@link Copies}. See {@link WorkingCopyService} for the validation loop as a
 * whole.
 *
 * <p>
 * Lock order: a caller that holds a copy's stripe lock in the service ({@code delete}) may take {@link #runs}; code
 * that holds {@link #runs} never takes a stripe lock.
 */
@Slf4j
final class WorkingCopyRuns {

    static final String UNEXPECTED_ERROR = "The validation could not be completed because of an unexpected server "
        + "error. Please try again later.";
    private static final Duration MIN_RETRY = Duration.ofSeconds(1);
    /** Shown to the user when the queue of the copies' runs is full. */
    static final String RUNS_BUSY = "Too many files are being checked. Please try again in a moment.";
    /** Why a run asked to check the CV rules did not, when the engine gave no reason. */
    static final String CV_NOT_CHECKED = "The CV rules were not checked: the file could not be read.";
    private static final TypeReference<List<ValidationMessage>> MESSAGES = new TypeReference<>() {
    };

    /** What the runs need of the service's copies. */
    interface Copies {

        /** The copy's database, or empty if there is no complete copy or it cannot be opened (logged). */
        Optional<WorkingCopyDb> openQuietly(UUID id);

        /** As {@link WorkingCopyService#settings}. */
        Optional<CopySettings> settings(UUID id, WorkingCopyDb db);
    }

    /** A running run as {@link WorkingCopyService#info} shows it: its revision (-1 until assembled) and kind. */
    record Running(long revision, boolean semantic) {
    }

    /**
     * The validation run of a copy, from its request until it ends. Guarded by
     * {@link #runs}.
     */
    private static final class Run {

        final String generation;
        boolean semantic;
        /** The revision being validated; -1 until its text is assembled. */
        long revision = -1;
        /** The newest revision an edit reported during the run. */
        long latest = -1;
        boolean again;
        boolean againSemantic;
        Instant deadline;

        Run(String generation, boolean semantic, Instant deadline) {
            this.generation = generation;
            this.semantic = semantic;
            this.deadline = deadline;
        }
    }

    private final Copies copies;
    private final StorageService storage;
    private final EngineRegistry registry;
    private final EngineResultReader results;
    private final EnginesProperties engines;
    private final ValidationProfileCatalog catalog;
    private final GuidanceResolver guidance;
    private final ObjectMapper json;
    private final TaskScheduler scheduler;
    private final Executor executor;
    private final Map<UUID, Run> runs = new LinkedHashMap<>();
    /** Test hook: called in a run after its result is stored and before it decides whether to run again. */
    @Nullable
    private volatile Consumer<UUID> beforeRunEnds;
    /** Test hook: called by a scheduled retry before it asks for a thread; it may block to hold the retry back. */
    @Nullable
    private volatile Consumer<UUID> retryGate;
    /** Test hook: called when a retry found no room in the runs' pool and was scheduled again. */
    @Nullable
    private volatile Consumer<UUID> retryDeferred;

    WorkingCopyRuns(Copies copies, StorageService storage, EngineRegistry registry, EngineResultReader results,
        EnginesProperties engines, ValidationProfileCatalog catalog, GuidanceResolver guidance, ObjectMapper json,
        TaskScheduler scheduler, Executor executor) {
        this.copies = copies;
        this.storage = storage;
        this.registry = registry;
        this.results = results;
        this.engines = engines;
        this.catalog = catalog;
        this.guidance = guidance;
        this.json = json;
        this.scheduler = scheduler;
        this.executor = executor;
    }

    // ---- what the service reads and changes ----

    /**
     * After a committed change of {@code revision}: marks a running run of the copy to run again when it ends, on
     * that revision or a later one.
     */
    void markChanged(UUID id, long revision) {
        synchronized (runs) {
            Run run = runs.get(id);
            if (run != null) {
                if (!run.again) {
                    run.again = true;
                    run.againSemantic = run.semantic;
                }
                run.latest = Math.max(run.latest, revision);
            }
        }
    }

    /** The copy's running run, its revision and kind read under one lock; null if none runs. */
    @Nullable
    Running snapshot(UUID id) {
        synchronized (runs) {
            Run run = runs.get(id);
            return run == null ? null : new Running(run.revision, run.semantic);
        }
    }

    /** Whether a run of the copy is going on. */
    boolean isRunning(UUID id) {
        synchronized (runs) {
            return runs.containsKey(id);
        }
    }

    /** Forgets the copy's run, which then ends without a result; for a deleted copy. */
    void remove(UUID id) {
        synchronized (runs) {
            runs.remove(id);
        }
    }

    // ---- the runs ----

    /**
     * Starts a validation of the current revision, or, while one runs, marks it to run again on the latest
     * revision when it ends.
     *
     * @throws WorkingCopyBusyException if the queue of the copies' runs is full
     */
    void requestValidation(UUID id, WorkingCopyDb db, boolean semantic) {
        String generation = db.meta(META_GENERATION);
        Run started = null;
        synchronized (runs) {
            Run run = runs.get(id);
            if (run == null) {
                started = new Run(generation, semantic, deadline());
                runs.put(id, started);
            } else {
                // a CV check asked for is not replaced by a structural request after it
                run.againSemantic = run.again ? run.againSemantic || semantic : semantic;
                run.again = true;
            }
        }
        if (started != null) {
            submit(id, started);
        }
    }

    private Instant deadline() {
        return Instant.now().plus(engines.jobTimeout());
    }

    private void submit(UUID id, Run run) {
        try {
            executor.execute(() -> runLoop(id, run));
        } catch (RejectedExecutionException e) {
            synchronized (runs) {
                runs.remove(id, run);
            }
            throw new WorkingCopyBusyException(RUNS_BUSY, e);
        }
    }

    /** Runs until the run has ended and no run again is due, or until it waits for a busy engine. */
    private void runLoop(UUID id, Run run) {
        while (true) {
            boolean semantic;
            synchronized (runs) {
                if (runs.get(id) != run) {
                    return;
                }
                semantic = run.semantic;
            }
            Optional<WorkingCopyDb> db = copies.openQuietly(id);
            if (db.isEmpty()) {
                synchronized (runs) {
                    runs.remove(id, run);
                }
                return;
            }
            Duration retry = null;
            try {
                retry = validateOnce(id, db.get(), run, semantic);
            } catch (IOException | RuntimeException e) {
                if (copies.openQuietly(id).isEmpty()) {
                    log.debug("Working copy {} was deleted during its validation", id);
                } else {
                    log.error("Working copy {}: the validation failed", id, e);
                    long revision;
                    synchronized (runs) {
                        revision = run.revision;
                    }
                    persistQuietly(id, db.get(), run, WorkingCopyValidation.failed(
                        revision >= 0 ? revision : revisionQuietly(db.get()), semantic, UNEXPECTED_ERROR), null);
                }
            }
            if (retry != null) {
                schedule(id, run, retry);
                return;
            }
            Consumer<UUID> hook = beforeRunEnds;
            if (hook != null) {
                hook.accept(id);
            }
            synchronized (runs) {
                if (runs.get(id) != run) {
                    return;
                }
                // read under the lock: an edit committed before this read runs again even if its changed() has
                // not marked the run yet; one committed after it finds the run ended and leaves stale=true
                long current = revisionQuietly(db.get());
                boolean newer = run.revision >= 0 && current >= 0 && current != run.revision;
                // a CV check covers a structural one of the same revision, not the other way round
                boolean again = newer || run.again && (run.latest > run.revision
                    || run.againSemantic && !run.semantic || run.revision < 0 && current >= 0);
                if (!again) {
                    runs.remove(id);
                    return;
                }
                run.semantic = run.again ? run.againSemantic : run.semantic;
                run.again = false;
                run.revision = -1;
                run.latest = -1;
                run.deadline = deadline();
            }
        }
    }

    private void schedule(UUID id, Run run, Duration delay) {
        try {
            scheduler.schedule(() -> {
                Consumer<UUID> gate = retryGate;
                if (gate != null) {
                    gate.accept(id);
                }
                try {
                    executor.execute(() -> runLoop(id, run));
                } catch (RejectedExecutionException e) {
                    // the copies' pool and queue are full right now: not a failure of the run, ask again shortly
                    // for as long as the run may last
                    boolean again;
                    synchronized (runs) {
                        again = runs.get(id) == run && Instant.now().plus(MIN_RETRY).isBefore(run.deadline);
                    }
                    if (again) {
                        log.info("Working copy {}: no room for another attempt yet; trying again in {} s", id,
                            MIN_RETRY.toSeconds());
                        schedule(id, run, MIN_RETRY);
                        Consumer<UUID> hook = retryDeferred;
                        if (hook != null) {
                            hook.accept(id);
                        }
                    } else {
                        abandon(id, run, e);
                    }
                }
            }, Instant.now().plus(delay));
        } catch (RuntimeException e) {
            abandon(id, run, e);
        }
    }

    private void abandon(UUID id, Run run, RuntimeException e) {
        log.error("Working copy {}: could not schedule another attempt", id, e);
        copies.openQuietly(id).ifPresent(db -> {
            boolean semantic;
            long revision;
            synchronized (runs) {
                semantic = run.semantic;
                revision = run.revision;
            }
            persistQuietly(id, db, run, WorkingCopyValidation.failed(revision, semantic, UNEXPECTED_ERROR), null);
        });
        synchronized (runs) {
            runs.remove(id, run);
        }
    }

    /**
     * One attempt: assembles the text, routes and validates it, and stores the
     * ended run.
     *
     * @return when to try again, for a busy engine; null if the run has ended
     */
    @Nullable
    private Duration validateOnce(UUID id, WorkingCopyDb db, Run run, boolean semantic) throws IOException {
        Optional<CopySettings> found = copies.settings(id, db);
        if (found.isEmpty()) {
            // the session is being deleted
            return null;
        }
        CopySettings settings = found.get();
        Path text = Files.createTempFile("mztab-workingcopy-", ".mztab");
        Path mapping = null;
        try {
            long revision;
            // the last layout change up to the validated text, from the same snapshot as the text
            long layout;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(text))) {
                WorkingCopyDb.Snapshot written = db.writeRevisionTo(out, WorkingCopyEditor.LAYOUT_REVISION_KEY);
                revision = written.revision();
                layout = written.meta()[0] == null ? 0L : Long.parseLong(written.meta()[0]);
            }
            synchronized (runs) {
                run.revision = revision;
            }
            Optional<String> declared = DeclaredVersions.ofTsv(text);
            RouteDecision decision = registry.route(declared);
            Optional<String> rejection = EngineMessages.rejection(decision, engines.unavailableHintUrl());
            if (rejection.isPresent()) {
                List<ValidationMessage> messages = List.of(ValidationMessage.error(rejection.get()));
                // nothing was checked, the CV rules neither
                persist(id, db, run, done(db, revision, layout, false, false, declared.orElse(null), messages, text,
                    null),
                    declared.orElse(""));
                return null;
            }
            RouteDecision.Routed routed = (RouteDecision.Routed) decision;
            List<ValidationMessage> notes = new ArrayList<>();
            if (semantic) {
                Optional<String> mismatch = profileMismatch(settings.profile(), routed.line());
                if (mismatch.isPresent()) {
                    persist(id, db, run, WorkingCopyValidation.failed(revision, true, mismatch.get()), null);
                    return null;
                }
                mapping = mappingFile(id, settings.profile(), routed.line(), notes);
            }
            try {
                ValidationEvent.Outcome outcome = routed.engine().validate(text, mapping, settings.level(),
                    settings.maxErrors(), results::outcomeOnly);
                List<ValidationMessage> messages = new ArrayList<>(notes);
                if (outcome.messages() != null) {
                    messages.addAll(outcome.messages());
                }
                String version = outcome.mzTabVersion() == null || outcome.mzTabVersion().isBlank()
                    ? declared.orElse(null) : outcome.mzTabVersion();
                // the CV rules were not checked without a mapping (no default profile for the line), nor if the
                // file could not be read into a model or the check broke: the engine then says so by its code
                List<ValidationMessage> notChecked = messages.stream().filter(MessageDiff::isCvNotChecked).toList();
                boolean cvChecked = semantic && mapping != null && outcome.parsed() && notChecked.isEmpty();
                String why = !semantic || cvChecked ? null : notChecked.isEmpty() ? CV_NOT_CHECKED
                    : String.join("\n", notChecked.stream().map(ValidationMessage::getMessage).toList());
                messages.removeAll(notChecked);
                persist(id, db, run, done(db, revision, layout, cvChecked, outcome.parsed(), version, messages, text,
                    why),
                    version == null ? "" : version);
                return null;
            } catch (EngineBusyException busy) {
                Duration delay = busy.retryAfter().compareTo(MIN_RETRY) < 0 ? MIN_RETRY : busy.retryAfter();
                Instant deadline;
                synchronized (runs) {
                    deadline = run.deadline;
                }
                if (Instant.now().plus(delay).isAfter(deadline)) {
                    log.warn("Working copy {}: the engine for mzTab-M {} was busy for longer than {}", id,
                        routed.line(), engines.jobTimeout());
                    persist(id, db, run, WorkingCopyValidation.failed(revision, semantic,
                        EngineMessages.unavailable(routed.line())), null);
                    return null;
                }
                log.info("Working copy {}: the engine is busy; trying again in {} s", id, delay.toSeconds());
                return delay;
            } catch (EngineUnavailableException e) {
                log.warn("Working copy {}: the engine for mzTab-M {} is unavailable: {}", id, routed.line(),
                    e.getCause() == null ? e.getMessage() : e.getCause().toString());
                persist(id, db, run, WorkingCopyValidation.failed(revision, semantic,
                    EngineMessages.unavailable(routed.line())), null);
                return null;
            } catch (EngineRequestException e) {
                log.warn("Working copy {}: the engine for mzTab-M {} refused the text: {} {}", id, routed.line(),
                    e.error().code(), e.error().message());
                persist(id, db, run, WorkingCopyValidation.failed(revision, semantic, e.error().message()), null);
                return null;
            } catch (EngineRejection e) {
                persist(id, db, run, WorkingCopyValidation.failed(revision, semantic, e.messages().isEmpty()
                    ? UNEXPECTED_ERROR : e.messages().get(0).getMessage()), null);
                return null;
            }
        } finally {
            SynchronousEngineService.deleteQuietly(text);
            SynchronousEngineService.deleteQuietly(mapping);
        }
    }

    /** A semantic run with a catalog profile of another version line cannot use its rules. */
    Optional<String> profileMismatch(@Nullable ProfileRef profile, String line) {
        if (profile == null || profile.kind() != ProfileRef.Kind.CATALOG) {
            return Optional.empty();
        }
        Optional<ValidationProfile> p = catalog.find(profile.id(), profile.version());
        if (p.isEmpty()) {
            return Optional.of("The validation profile " + profile.id() + " " + profile.version()
                + " is no longer available.");
        }
        Set<String> lines = new LinkedHashSet<>();
        p.get().mzTabVersions().forEach(v -> MzTabVersions.lineOf(v).ifPresent(lines::add));
        if (lines.isEmpty() || lines.contains(line)) {
            return Optional.empty();
        }
        return Optional.of(EngineMessages.profileOfOtherLine(profile.id(), profile.version(), lines, line));
    }

    /**
     * The mapping of the copy's profile, or of the line's default profile for a copy without one. A custom mapping is
     * an upload's, in its session's slot.
     */
    @Nullable
    Path mappingFile(UUID id, @Nullable ProfileRef profile, String line, List<ValidationMessage> notes)
        throws IOException {
        ProfileRef.Kind kind = profile == null ? ProfileRef.Kind.NONE : profile.kind();
        switch (kind) {
            case CATALOG -> {
                ValidationProfile p = catalog.find(profile.id(), profile.version()).orElseThrow();
                return toTempFile(catalog.mappingUrl(p).openStream(), ".xml");
            }
            case CUSTOM -> {
                return toTempFile(storage.read(id, SLOT.MAPPINGFILE, ValidationSessionService.CUSTOM_MAPPING)
                    .orElseThrow(() -> new IllegalStateException("The custom mapping of validation " + id
                    + " is missing")), ".xml");
            }
            default -> {
                Optional<ValidationProfile> p = catalog.defaultFor(line);
                if (p.isEmpty()) {
                    // no rules to check: said as the engine says it did not check them
                    ValidationMessage note = ValidationMessage.info(EngineMessages.noDefaultProfile(line));
                    note.setCode(ValidationMessage.CV_NOT_CHECKED);
                    note.setCategory(ValidationMessage.Category.CROSS_CHECK);
                    notes.add(note);
                    return null;
                }
                return toTempFile(catalog.mappingUrl(p.get()).openStream(), ".xml");
            }
        }
    }

    /**
     * An ended run with a result, compared with the messages shown before it ({@link MessageDiff#compareRun}: a
     * structural run keeps the CV messages of the last CV check), with guidance from the validated text. The
     * lines of kept CV messages are quoted only if no line moved since their check: their numbers are of the text
     * of that check, and the validated text may have other lines under them.
     *
     * @param layout the revision of the last layout change up to the validated text
     * @param semantic whether the run checked the CV rules
     * @param cvNotChecked why a run asked to check them did not
     */
    private WorkingCopyValidation done(WorkingCopyDb db, long revision, long layout, boolean semantic,
        boolean parsed, @Nullable String version, List<ValidationMessage> found, Path text,
        @Nullable String cvNotChecked) throws IOException {
        MessageDiff.Result diff = MessageDiff.compareRun(baseline(db, false), baseline(db, true), found, semantic);
        List<ValidationMessage> messages = diff.current().stream().map(TrackedMessage::message).toList();
        // one run per copy at a time, and only a run writes it: the CV check whose messages a structural run keeps
        String cvRevision = db.meta(META_CV_REVISION);
        boolean cvLinesMoved = !semantic && cvRevision != null && layout > Long.parseLong(cvRevision);
        String line = MzTabVersions.lineOf(version).orElse(null);
        List<String> software;
        try (InputStream in = Files.newInputStream(text)) {
            software = UploadLines.softwareNames(in);
        }
        List<Guidance> resolved = guidance.resolve(messages, line, software, numbers -> {
            try (InputStream in = Files.newInputStream(text)) {
                return UploadLines.lines(in, numbers);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, i -> !(cvLinesMoved && MessageDiff.isCvRule(messages.get(i))));
        return new WorkingCopyValidation(Status.DONE, revision, false, parsed, parsed ? null : readUpTo(messages,
            resolved), semantic, diff.current(), diff.fixed(), resolved, null, cvNotChecked);
    }

    /** The smallest line of a message whose guidance says the engine stops reading there. */
    @Nullable
    static Long readUpTo(List<ValidationMessage> messages, List<Guidance> guidance) {
        Long first = null;
        for (Guidance g : guidance) {
            if (!g.stopsParsing()) {
                continue;
            }
            for (int index : g.messages()) {
                Long n = messages.get(index).getLineNumber();
                if (n != null && n > 0 && (first == null || n < first)) {
                    first = n;
                }
            }
        }
        return first;
    }

    /**
     * Stores an ended run. The structural messages of a DONE run become the
     * comparison for the next run; the CV messages of a DONE CV check become
     * the CV messages shown until the next one, with its revision. Nothing is
     * stored into another copy than the run's.
     *
     * @param version the version to record, "" for none, or null to keep the recorded one
     */
    private void persist(UUID id, WorkingCopyDb db, Run run, WorkingCopyValidation v, @Nullable String version) {
        String last;
        String structural;
        String cv;
        try {
            last = json.writeValueAsString(v);
            List<ValidationMessage> messages = v.messages().stream().map(TrackedMessage::message).toList();
            structural = json.writeValueAsString(ofKind(messages, false));
            cv = json.writeValueAsString(ofKind(messages, true));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the validation of working copy " + id, e);
        }
        db.inTransaction(() -> {
            if (!Objects.equals(run.generation, db.meta(META_GENERATION))) {
                return null;
            }
            db.setMeta(META_LAST, last);
            if (v.status() == Status.DONE) {
                db.setMeta(META_LAST_DONE, last);
                db.setMeta(META_LAST_DONE_AT, Instant.now().toString());
                db.setMeta(META_BASELINE_STRUCTURAL, structural);
                if (v.semantic()) {
                    db.setMeta(META_BASELINE_SEMANTIC, cv);
                    db.setMeta(META_CV_REVISION, Long.toString(v.revision()));
                }
            }
            if (version != null) {
                db.setMeta(META_VERSION, version);
            }
            return null;
        });
    }

    private void persistQuietly(UUID id, WorkingCopyDb db, Run run, WorkingCopyValidation v,
        @Nullable String version) {
        try {
            persist(id, db, run, v, version);
        } catch (RuntimeException e) {
            log.warn("Working copy {}: could not store the failed validation", id, e);
        }
    }

    /** The CV messages ({@code cv}) or the structural ones of {@code messages}. */
    static List<ValidationMessage> ofKind(List<ValidationMessage> messages, boolean cv) {
        return messages.stream().filter(m -> MessageDiff.isCvRule(m) == cv).toList();
    }

    /** The stored structural messages, or ({@code cv}) the CV messages of the last CV check. */
    private List<ValidationMessage> baseline(WorkingCopyDb db, boolean cv) {
        String stored = db.meta(cv ? META_BASELINE_SEMANTIC : META_BASELINE_STRUCTURAL);
        if (stored == null) {
            return List.of();
        }
        try {
            // a copy stored before the kinds were kept apart has a run's whole list under either key
            return ofKind(json.readValue(stored, MESSAGES), cv);
        } catch (JsonProcessingException e) {
            log.warn("Could not read the stored messages of a working copy; comparing with none", e);
            return List.of();
        }
    }

    private static long revisionQuietly(WorkingCopyDb db) {
        try {
            return db.revision();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---- test hooks ----

    /** For tests only: see {@link #retryGate}. */
    void retryGate(@Nullable Consumer<UUID> hook) {
        this.retryGate = hook;
    }

    /** For tests only: see {@link #retryDeferred}. */
    void retryDeferred(@Nullable Consumer<UUID> hook) {
        this.retryDeferred = hook;
    }

    /** For tests only: see {@link #beforeRunEnds}. */
    void beforeRunEnds(@Nullable Consumer<UUID> hook) {
        this.beforeRunEnds = hook;
    }
}
