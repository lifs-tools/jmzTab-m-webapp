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
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.MzTabVersionDetector;
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
import org.lifstools.mztab.validator.webapp.service.storage.StorageProperties;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousEngineService;
import org.lifstools.mztab.validator.webapp.service.validation.SynchronousEngineService.Conversion;
import org.lifstools.mztab.validator.webapp.session.ProfileRef;
import org.lifstools.mztab.validator.webapp.session.SectionsSummary;
import org.lifstools.mztab.validator.webapp.session.StorageFullException;
import org.lifstools.mztab.validator.webapp.session.TableSize;
import org.lifstools.mztab.validator.webapp.session.ValidationRecord;
import org.lifstools.mztab.validator.webapp.session.ValidationSessionService;
import org.lifstools.mztab.validator.webapp.session.ValidationStatus;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfile;
import org.lifstools.mztab.validator.webapp.validationprofile.ValidationProfileCatalog;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyValidation.Status;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/**
 * Working copies of uploaded files: creation from a validation session, edits
 * with undo and redo ({@link WorkingCopyEditor}), the validation loop and the
 * downloads. A copy lives in the session's {@code WORKINGCOPY} slot as one
 * SQLite file ({@link WorkingCopyDb}); its id is the session's id, so each
 * session has at most one copy, and deleting the session deletes it.
 *
 * <p>
 * <b>Database handles.</b> A {@link WorkingCopyDb} holds no open connection
 * (each statement takes its own), so it is opened per request and never
 * cached: nothing keeps a copy's file open after its session is deleted, and
 * nothing is left to forget. Only {@link #createOrFind} creates the slot
 * directory; every other operation finds the file without creating anything
 * ({@link StorageService#existingFile}), so a deleted session is not
 * recreated by a late request or run. A copy counts as existing once its
 * creation has completed (meta {@code copy.complete}).
 *
 * <p>
 * <b>The validation loop.</b> A run assembles the text of one revision (the
 * revision and the lines come from one snapshot), detects its declared version
 * as an upload's is, and sends it to that version's engine with the session's
 * level, error limit and, for a semantic run, profile. It runs on
 * {@code toolThreadPoolTaskExecutor}; one run per copy at a time. A request
 * during a run, and an edit, undo or redo during a run, mark it to run again
 * when it ends, on the then latest revision; the run again is skipped if it
 * would validate the same revision the same way. A busy engine is asked again
 * after its {@code Retry-After}, until {@code engines.job-timeout} after the
 * run started. The ended run, and per kind (structural or semantic) its
 * messages for the next comparison, are stored in the copy's meta table, so
 * they survive a restart; a running run is only held in memory. Each copy has
 * a generation id, so a run that outlives its copy (deleted and created again)
 * writes nothing into the new one.
 *
 * <p>
 * <b>Storage.</b> Creation needs three times the upload size of free capacity
 * (the SQLite overhead estimate). Edits, undo and redo are refused once the
 * total stored size has reached {@code storage.max-total-bytes}; the total is
 * measured at most every {@value #USAGE_MAX_AGE_SECONDS} seconds, as walking
 * the storage tree on every keystroke batch would cost more than the edit. One
 * batch can overshoot the cap by at most its undo budget
 * ({@link WorkingCopyEditor}).
 */
@Slf4j
@Service
public class WorkingCopyService {

    static final String DB_FILE = "copy.db";
    static final String UNKNOWN_VALIDATION = "Unknown or expired validation.";
    static final String UNEXPECTED_ERROR = "The validation could not be completed because of an unexpected server "
        + "error. Please try again later.";
    /** Free capacity needed to create a copy, as a multiple of the upload size. */
    static final int CREATE_OVERHEAD = 3;
    static final long USAGE_MAX_AGE_SECONDS = 5;
    private static final Duration MIN_RETRY = Duration.ofSeconds(1);
    private static final int STRIPES = 64;
    private static final int FACTS_CACHE_SIZE = 256;

    private static final String META_COMPLETE = "copy.complete";
    private static final String META_GENERATION = "copy.generation";
    private static final String META_FILE_NAME = "copy.fileName";
    private static final String META_ENCODING = "copy.encoding";
    private static final String META_CHANGED = "copy.changedAt";
    private static final String META_VERSION = "copy.mzTabVersion";
    private static final String META_LAST = "validation.last";
    private static final String META_BASELINE_STRUCTURAL = "validation.baseline.structural";
    private static final String META_BASELINE_SEMANTIC = "validation.baseline.semantic";
    private static final TypeReference<List<ValidationMessage>> MESSAGES = new TypeReference<>() {
    };

    /** The result of {@link #createOrFind}. */
    public record Creation(WorkingCopyInfo info, boolean created) {
    }

    /** The line count and sections of one revision, which do not change. */
    private record Facts(long lineCount, SectionsSummary sections) {
    }

    /** The generation tells a re-created copy (revision 0 again) from the one before. */
    private record FactsKey(UUID id, @Nullable String generation, long revision) {
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

    private final StorageService storage;
    private final StorageProperties storageProperties;
    private final ValidationSessionService sessions;
    private final EngineRegistry registry;
    private final EngineResultReader results;
    private final EnginesProperties engines;
    private final ValidationProfileCatalog catalog;
    private final SynchronousEngineService conversions;
    private final GuidanceResolver guidance;
    private final ObjectMapper json;
    private final TaskScheduler scheduler;
    private final Executor executor;
    private final long retentionDays;
    private final WorkingCopyEditor editor = new WorkingCopyEditor();
    private final Object[] locks = new Object[STRIPES];
    private final Map<UUID, Run> runs = new LinkedHashMap<>();
    private final Map<FactsKey, Facts> facts = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<FactsKey, Facts> eldest) {
            return size() > FACTS_CACHE_SIZE;
        }
    };
    /** Test hook: called in a run after its result is stored and before it decides whether to run again. */
    @Nullable
    private volatile Consumer<UUID> beforeRunEnds;
    private volatile long usageBytes;
    private volatile long usageMeasuredAt;
    private volatile boolean usageKnown;

    public WorkingCopyService(StorageService storage, StorageProperties storageProperties,
        ValidationSessionService sessions, EngineRegistry registry, EngineResultReader results,
        EnginesProperties engines, ValidationProfileCatalog catalog, SynchronousEngineService conversions,
        GuidanceResolver guidance, ObjectMapper json, TaskScheduler scheduler,
        @Qualifier("toolThreadPoolTaskExecutor") Executor executor,
        @Value("${minCleanupAge:7}") long retentionDays) {
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.sessions = sessions;
        this.registry = registry;
        this.results = results;
        this.engines = engines;
        this.catalog = catalog;
        this.conversions = conversions;
        this.guidance = guidance;
        this.json = json;
        this.scheduler = scheduler;
        this.executor = executor;
        this.retentionDays = retentionDays;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    // ---- creation and deletion ----

    /**
     * The copy of a validation, created from its upload if there is none yet.
     * An existing copy is returned unchanged, whatever {@code encoding} says.
     * A JSON upload is converted to tab separated text by the engine of its
     * version first (then read as UTF-8).
     *
     * @param encoding how the upload's bytes are decoded
     * @throws NoSuchWorkingCopyException if there is no such validation
     * @throws NotUtf8Exception for {@link SourceEncoding#UTF_8} and an upload
     * that is not UTF-8; nothing is stored
     * @throws TooLargeException for a file of more than {@value CopyText#MAX_LINES} lines or a line of more than
     * {@value CopyText#MAX_CELLS} cells, found while it is read; nothing is stored
     * @throws StorageFullException if less than three times the upload size is free
     * @throws IllegalArgumentException if the engine cannot convert a JSON upload
     */
    public Creation createOrFind(UUID validationId, SourceEncoding encoding) {
        if (encoding == null) {
            throw new IllegalArgumentException("An encoding is required.");
        }
        synchronized (lock(validationId)) {
            ValidationRecord record = sessions.find(validationId)
                .orElseThrow(() -> new NoSuchWorkingCopyException(UNKNOWN_VALIDATION));
            Optional<WorkingCopyDb> existing = openExisting(validationId);
            if (existing.isPresent()) {
                return new Creation(info(validationId, existing.get()), false);
            }
            return new Creation(create(record, encoding), true);
        }
    }

    /** As {@link #createOrFind}, for callers that do not need to know whether the copy is new. */
    public WorkingCopyInfo create(UUID validationId, SourceEncoding encoding) {
        return createOrFind(validationId, encoding).info();
    }

    private WorkingCopyInfo create(ValidationRecord record, SourceEncoding encoding) {
        UUID id = record.id();
        Path upload = null;
        Path converted = null;
        try {
            upload = toTempFile(storage.read(id, SLOT.MZTABFILE, ValidationSessionService.UPLOAD)
                .orElseThrow(() -> new NoSuchWorkingCopyException(UNKNOWN_VALIDATION)), ".mztab");
            long size = Files.size(upload);
            if (storageProperties.getMaxTotalBytes() - storage.totalBytes() < CREATE_OVERHEAD * size) {
                throw new StorageFullException();
            }
            Path text;
            SourceEncoding textEncoding;
            if (MzTabVersionDetector.isJson(upload)) {
                converted = toTsv(upload);
                // the engine writes UTF-8
                text = converted;
                textEncoding = SourceEncoding.UTF_8;
            } else {
                text = upload;
                textEncoding = encoding;
            }
            // a copy whose creation broke off earlier is replaced
            storage.delete(id, SLOT.WORKINGCOPY);
            try {
                WorkingCopyDb db = WorkingCopyDb.open(storage.localFile(id, SLOT.WORKINGCOPY, DB_FILE));
                // streamed, and refused while it is read if it exceeds a cap; the slot is then deleted below
                try (InputStream in = Files.newInputStream(text)) {
                    db.importFrom(in, textEncoding);
                }
                db.setMeta(META_GENERATION, UUID.randomUUID().toString());
                db.setMeta(META_FILE_NAME, record.fileName());
                db.setMeta(META_ENCODING, encoding.name());
                db.setMeta(META_VERSION, record.mzTabVersion() == null ? "" : record.mzTabVersion());
                db.setMeta(META_CHANGED, Instant.now().toString());
                if (record.status() == ValidationStatus.FINISHED) {
                    // the first run of each kind is compared with the session's own validation of that kind
                    boolean semantic = record.validationProfile() != null
                        && record.validationProfile().kind() != ProfileRef.Kind.NONE;
                    db.setMeta(baselineKey(semantic), json.writeValueAsString(record.messages()));
                }
                db.setMeta(META_COMPLETE, "true");
                storage.touch(id);
                return info(id, db);
            } catch (RuntimeException | IOException e) {
                deleteSlotQuietly(id);
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the working copy of validation " + id, e);
        } finally {
            SynchronousEngineService.deleteQuietly(upload);
            SynchronousEngineService.deleteQuietly(converted);
        }
    }

    private Path toTsv(Path jsonUpload) throws IOException {
        Conversion conversion = conversions.jsonToTsv(jsonUpload, false);
        if (conversion instanceof Conversion.Converted c) {
            return c.file();
        }
        List<ValidationMessage> messages = ((Conversion.Rejected) conversion).messages();
        throw new IllegalArgumentException("The JSON file could not be converted to tab separated mzTab-M: "
            + (messages.isEmpty() ? "no reason given." : messages.get(0).getMessage()));
    }

    /**
     * Deletes the copy; its session stays. A run of it that is going on ends
     * without a result.
     *
     * @throws NoSuchWorkingCopyException if there is no copy
     */
    public void delete(UUID id) {
        synchronized (lock(id)) {
            if (storage.existingFile(id, SLOT.WORKINGCOPY, DB_FILE).isEmpty()) {
                throw new NoSuchWorkingCopyException();
            }
            synchronized (runs) {
                runs.remove(id);
            }
            storage.delete(id, SLOT.WORKINGCOPY);
            synchronized (facts) {
                facts.keySet().removeIf(k -> k.id().equals(id));
            }
        }
    }

    // ---- reading ----

    /** @throws NoSuchWorkingCopyException if there is no copy */
    public WorkingCopyInfo info(UUID id) {
        return guard(id, () -> info(id, db(id)));
    }

    /**
     * The name of the uploaded file, without computing anything else.
     *
     * @throws NoSuchWorkingCopyException if there is no copy
     */
    public String fileName(UUID id) {
        return guard(id, () -> db(id).meta(META_FILE_NAME));
    }

    /**
     * Runs {@code work} on the database of an existing copy, for read-only
     * callers outside this package such as the quick fixes. A copy deleted
     * meanwhile is answered as no copy.
     *
     * @throws NoSuchWorkingCopyException if there is no copy
     */
    public <T> T read(UUID id, java.util.function.Function<WorkingCopyDb, T> work) {
        return guard(id, () -> work.apply(db(id)));
    }

    /**
     * The database of an existing copy, opened for one request.
     *
     * @throws NoSuchWorkingCopyException if there is no complete copy
     */
    WorkingCopyDb db(UUID id) {
        return openExisting(id).orElseThrow(NoSuchWorkingCopyException::new);
    }

    private Optional<WorkingCopyDb> openExisting(UUID id) {
        Optional<Path> file = storage.existingFile(id, SLOT.WORKINGCOPY, DB_FILE);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        WorkingCopyDb db;
        try {
            db = WorkingCopyDb.open(file.get());
        } catch (RuntimeException e) {
            if (storage.existingFile(id, SLOT.WORKINGCOPY, DB_FILE).isEmpty()) {
                // deleted meanwhile
                return Optional.empty();
            }
            throw e;
        }
        return "true".equals(db.meta(META_COMPLETE)) ? Optional.of(db) : Optional.empty();
    }

    private WorkingCopyInfo info(UUID id, WorkingCopyDb db) {
        // the revision first: facts read after it may be newer, never older (see facts)
        long revision = db.revision();
        Facts f = facts(id, db, revision);
        boolean canUndo = editor.canUndo(db);
        boolean canRedo = editor.canRedo(db);
        WorkingCopyValidation last = lastValidation(db);
        WorkingCopyValidation shown = last;
        synchronized (runs) {
            Run run = runs.get(id);
            if (run != null) {
                long validated = run.revision >= 0 ? run.revision : revision;
                shown = last == null ? WorkingCopyValidation.firstRunning(validated, run.semantic)
                    : last.running(validated, run.semantic);
            }
        }
        if (shown != null) {
            shown = shown.withStale(shown.revision() != revision);
        }
        String changed = db.meta(META_CHANGED);
        Instant expiresAt = (changed == null ? Instant.now() : Instant.parse(changed))
            .plus(Duration.ofDays(retentionDays));
        String version = db.meta(META_VERSION);
        return new WorkingCopyInfo(id, db.meta(META_FILE_NAME), revision,
            version == null || version.isEmpty() ? null : version, f.lineCount(), f.sections(), canUndo, canRedo,
            expiresAt, shown);
    }

    /**
     * The line count and sections of {@code revision}, cached: a revision's
     * text never changes. They are read after the revision, so in a race with
     * an edit they may be of a newer revision and are then cached under an
     * older one, which no later read asks for again (revisions only grow).
     */
    private Facts facts(UUID id, WorkingCopyDb db, long revision) {
        FactsKey key = new FactsKey(id, db.meta(META_GENERATION), revision);
        synchronized (facts) {
            Facts cached = facts.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Map<String, Long> counts = db.sectionCounts();
        long lineCount = counts.values().stream().mapToLong(Long::longValue).sum();
        SectionsSummary sections = new SectionsSummary(counts.getOrDefault("MTD", 0L).intValue(),
            tableSize(db, counts, "SMH", "SML"), tableSize(db, counts, "SFH", "SMF"),
            tableSize(db, counts, "SEH", "SME"));
        Facts computed = new Facts(lineCount, sections);
        synchronized (facts) {
            facts.put(key, computed);
        }
        return computed;
    }

    @Nullable
    private static TableSize tableSize(WorkingCopyDb db, Map<String, Long> counts, String header, String rows) {
        if (!counts.containsKey(header)) {
            return null;
        }
        return db.firstOf(header).map(h -> new TableSize(counts.getOrDefault(rows, 0L).intValue(),
            h.cells().size() - 1)).orElse(null);
    }

    // ---- editing ----

    /**
     * Applies a batch of edits ({@link WorkingCopyEditor#apply}).
     *
     * @throws StaleRevisionException if the batch is not of the current revision
     * @throws IllegalArgumentException for an invalid batch; nothing changes
     * @throws StorageFullException if the storage cap is reached
     */
    public WorkingCopyInfo edit(UUID id, EditBatch batch) {
        if (batch != null) {
            requireRevision(batch.revision());
        }
        return guard(id, () -> {
            WorkingCopyDb db = db(id);
            requireCapacity();
            long revision = editor.apply(db, batch);
            changed(id, db, revision);
            return info(id, db);
        });
    }

    /**
     * Reverts the newest change.
     *
     * @param revision the copy's current revision
     * @throws StaleRevisionException if it is not
     * @throws WorkingCopyConflictException if there is nothing to undo
     */
    public WorkingCopyInfo undo(UUID id, long revision) {
        return undoOrRedo(id, revision, true);
    }

    /** Re-applies the change undone last; as {@link #undo}. */
    public WorkingCopyInfo redo(UUID id, long revision) {
        return undoOrRedo(id, revision, false);
    }

    private WorkingCopyInfo undoOrRedo(UUID id, long revision, boolean undo) {
        requireRevision(revision);
        return guard(id, () -> undoOrRedo(id, db(id), revision, undo));
    }

    private WorkingCopyInfo undoOrRedo(UUID id, WorkingCopyDb db, long revision, boolean undo) {
        requireCapacity();
        // one transaction, so "nothing to do" and the change see the same state
        long next = db.inTransaction(() -> {
            long current = db.revision();
            if (current != revision) {
                throw new StaleRevisionException(current, revision);
            }
            if (undo ? !editor.canUndo(db) : !editor.canRedo(db)) {
                throw new WorkingCopyConflictException(undo ? "There is nothing to undo." : "There is nothing to redo.");
            }
            return undo ? editor.undo(db, revision) : editor.redo(db, revision);
        });
        changed(id, db, next);
        return info(id, db);
    }

    /** Restarts retention and marks a running validation to run again. */
    private void changed(UUID id, WorkingCopyDb db, long revision) {
        db.setMeta(META_CHANGED, Instant.now().toString());
        storage.touch(id);
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

    private void requireCapacity() {
        long now = System.nanoTime();
        if (!usageKnown || now - usageMeasuredAt > TimeUnit.SECONDS.toNanos(USAGE_MAX_AGE_SECONDS)) {
            usageBytes = storage.totalBytes();
            usageMeasuredAt = now;
            usageKnown = true;
        }
        if (usageBytes >= storageProperties.getMaxTotalBytes()) {
            throw new StorageFullException();
        }
    }

    // ---- validating ----

    /**
     * Starts a validation of the current revision, or, while one runs, marks
     * it to run again on the latest revision when it ends.
     *
     * @param semantic whether to check the CV rules too: of the session's
     * profile (catalog or custom mapping), or, for a session without one, of
     * the default profile of the text's version line
     * @return the copy, its validation {@link Status#RUNNING}
     */
    public WorkingCopyInfo requestValidation(UUID id, boolean semantic) {
        return guard(id, () -> requestValidation(id, db(id), semantic));
    }

    private WorkingCopyInfo requestValidation(UUID id, WorkingCopyDb db, boolean semantic) {
        String generation = db.meta(META_GENERATION);
        Run started = null;
        synchronized (runs) {
            Run run = runs.get(id);
            if (run == null) {
                started = new Run(generation, semantic, deadline());
                runs.put(id, started);
            } else {
                run.again = true;
                run.againSemantic = semantic;
            }
        }
        if (started != null) {
            submit(id, started);
        }
        return info(id, db);
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
            throw e;
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
            Optional<WorkingCopyDb> db = openQuietly(id);
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
                if (openQuietly(id).isEmpty()) {
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
                boolean again = newer || run.again && (run.latest > run.revision
                    || run.againSemantic != run.semantic || run.revision < 0 && current >= 0);
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
                try {
                    executor.execute(() -> runLoop(id, run));
                } catch (RejectedExecutionException e) {
                    abandon(id, run, e);
                }
            }, Instant.now().plus(delay));
        } catch (RuntimeException e) {
            abandon(id, run, e);
        }
    }

    private void abandon(UUID id, Run run, RuntimeException e) {
        log.error("Working copy {}: could not schedule another attempt", id, e);
        openQuietly(id).ifPresent(db -> {
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
        Optional<ValidationRecord> found = sessions.find(id);
        if (found.isEmpty()) {
            // the session is being deleted
            return null;
        }
        ValidationRecord record = found.get();
        Path text = Files.createTempFile("mztab-workingcopy-", ".mztab");
        Path mapping = null;
        try {
            long revision;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(text))) {
                revision = db.writeRevisionTo(out);
            }
            synchronized (runs) {
                run.revision = revision;
            }
            Optional<String> declared = DeclaredVersions.ofTsv(text);
            RouteDecision decision = registry.route(declared);
            Optional<String> rejection = EngineMessages.rejection(decision, engines.unavailableHintUrl());
            if (rejection.isPresent()) {
                List<ValidationMessage> messages = List.of(ValidationMessage.error(rejection.get()));
                persist(id, db, run, done(db, revision, semantic, false, declared.orElse(null), messages, text),
                    declared.orElse(""));
                return null;
            }
            RouteDecision.Routed routed = (RouteDecision.Routed) decision;
            List<ValidationMessage> notes = new ArrayList<>();
            if (semantic) {
                Optional<String> mismatch = profileMismatch(record, routed.line());
                if (mismatch.isPresent()) {
                    persist(id, db, run, WorkingCopyValidation.failed(revision, true, mismatch.get()), null);
                    return null;
                }
                mapping = mappingFile(record, routed.line(), notes);
            }
            try {
                ValidationEvent.Outcome outcome = routed.engine().validate(text, mapping, record.level(),
                    record.maxErrors(), results::outcomeOnly);
                List<ValidationMessage> messages = new ArrayList<>(notes);
                if (outcome.messages() != null) {
                    messages.addAll(outcome.messages());
                }
                String version = outcome.mzTabVersion() == null || outcome.mzTabVersion().isBlank()
                    ? declared.orElse(null) : outcome.mzTabVersion();
                persist(id, db, run, done(db, revision, semantic, outcome.parsed(), version, messages, text),
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
    private Optional<String> profileMismatch(ValidationRecord record, String line) {
        ProfileRef profile = record.validationProfile();
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

    /** The mapping of the session's profile, or of the line's default profile for a session without one. */
    @Nullable
    private Path mappingFile(ValidationRecord record, String line, List<ValidationMessage> notes)
        throws IOException {
        ProfileRef profile = record.validationProfile();
        ProfileRef.Kind kind = profile == null ? ProfileRef.Kind.NONE : profile.kind();
        switch (kind) {
            case CATALOG -> {
                ValidationProfile p = catalog.find(profile.id(), profile.version()).orElseThrow();
                return toTempFile(catalog.mappingUrl(p).openStream(), ".xml");
            }
            case CUSTOM -> {
                return toTempFile(storage.read(record.id(), SLOT.MAPPINGFILE, ValidationSessionService.CUSTOM_MAPPING)
                    .orElseThrow(() -> new IllegalStateException("The custom mapping of validation " + record.id()
                    + " is missing")), ".xml");
            }
            default -> {
                Optional<ValidationProfile> p = catalog.defaultFor(line);
                if (p.isEmpty()) {
                    notes.add(ValidationMessage.info(EngineMessages.noDefaultProfile(line)));
                    return null;
                }
                return toTempFile(catalog.mappingUrl(p.get()).openStream(), ".xml");
            }
        }
    }

    /** An ended run with a result: compared with the last run of its kind, with guidance from the validated text. */
    private WorkingCopyValidation done(WorkingCopyDb db, long revision, boolean semantic, boolean parsed,
        @Nullable String version, List<ValidationMessage> messages, Path text) throws IOException {
        MessageDiff.Result diff = MessageDiff.compare(baseline(db, semantic), messages);
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
        });
        return new WorkingCopyValidation(Status.DONE, revision, false, parsed, parsed ? null : readUpTo(messages,
            resolved), semantic, diff.current(), diff.fixed(), resolved, null);
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
     * Stores an ended run; a DONE run also becomes the comparison for the next
     * run of its kind. Nothing is stored into another copy than the run's.
     *
     * @param version the version to record, "" for none, or null to keep the recorded one
     */
    private void persist(UUID id, WorkingCopyDb db, Run run, WorkingCopyValidation v, @Nullable String version) {
        String last;
        String messages;
        try {
            last = json.writeValueAsString(v);
            messages = json.writeValueAsString(v.messages().stream().map(TrackedMessage::message).toList());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the validation of working copy " + id, e);
        }
        db.inTransaction(() -> {
            if (!Objects.equals(run.generation, db.meta(META_GENERATION))) {
                return null;
            }
            db.setMeta(META_LAST, last);
            if (v.status() == Status.DONE) {
                db.setMeta(baselineKey(v.semantic()), messages);
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

    private static String baselineKey(boolean semantic) {
        return semantic ? META_BASELINE_SEMANTIC : META_BASELINE_STRUCTURAL;
    }

    private List<ValidationMessage> baseline(WorkingCopyDb db, boolean semantic) {
        String stored = db.meta(baselineKey(semantic));
        if (stored == null) {
            return List.of();
        }
        try {
            return json.readValue(stored, MESSAGES);
        } catch (JsonProcessingException e) {
            log.warn("Could not read the stored messages of a working copy; comparing with none", e);
            return List.of();
        }
    }

    @Nullable
    private WorkingCopyValidation lastValidation(WorkingCopyDb db) {
        String stored = db.meta(META_LAST);
        if (stored == null) {
            return null;
        }
        try {
            return json.readValue(stored, WorkingCopyValidation.class);
        } catch (JsonProcessingException e) {
            log.warn("Could not read the stored validation of a working copy", e);
            return null;
        }
    }

    private Optional<WorkingCopyDb> openQuietly(UUID id) {
        try {
            return openExisting(id);
        } catch (RuntimeException e) {
            log.warn("Working copy {} could not be opened", id, e);
            return Optional.empty();
        }
    }

    private static long revisionQuietly(WorkingCopyDb db) {
        try {
            return db.revision();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---- downloads ----

    /** Writes the current text, byte for byte as it is stored. */
    public void writeTsv(UUID id, OutputStream out) throws IOException {
        guardIo(id, () -> db(id).writeTo(out));
    }

    /**
     * Writes the current text as mzTab-M JSON, converted by the engine of its
     * version. Nothing is written unless the conversion succeeded.
     *
     * @throws WorkingCopyConflictException unless the last validation ended
     * DONE on the current revision with the file read and without an error,
     * or if the engine cannot convert the text
     */
    public void writeJson(UUID id, OutputStream out) throws IOException {
        guardIo(id, () -> writeJson(id, db(id), out));
    }

    private void writeJson(UUID id, WorkingCopyDb db, OutputStream out) throws IOException {
        Path text = Files.createTempFile("mztab-workingcopy-", ".mztab");
        Path converted = null;
        try {
            long revision;
            try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(text))) {
                revision = db.writeRevisionTo(o);
            }
            WorkingCopyValidation last = lastValidation(db);
            if (last == null || last.revision() != revision || last.status() != Status.DONE) {
                throw new WorkingCopyConflictException("Validate the current text first: a JSON file is only "
                    + "written from a validated state.");
            }
            if (!last.valid()) {
                throw new WorkingCopyConflictException("The file has errors. Fix them before downloading it as JSON.");
            }
            Conversion conversion = conversions.tsvToJson(text);
            if (conversion instanceof Conversion.Rejected r) {
                throw new WorkingCopyConflictException("The file could not be converted to JSON: "
                    + (r.messages().isEmpty() ? "no reason given." : r.messages().get(0).getMessage()));
            }
            converted = ((Conversion.Converted) conversion).file();
            Files.copy(converted, out);
            out.flush();
        } finally {
            SynchronousEngineService.deleteQuietly(text);
            SynchronousEngineService.deleteQuietly(converted);
        }
    }

    /** Writes the file as it was uploaded to the session, tab separated or JSON. */
    public void writeOriginal(UUID id, OutputStream out) throws IOException {
        guard(id, () -> db(id));
        try (InputStream in = storage.read(id, SLOT.MZTABFILE, ValidationSessionService.UPLOAD)
            .orElseThrow(NoSuchWorkingCopyException::new)) {
            in.transferTo(out);
        }
        out.flush();
    }

    // ---- helpers ----

    /** For tests only: see {@link #beforeRunEnds}. */
    void beforeRunEnds(@Nullable Consumer<UUID> hook) {
        this.beforeRunEnds = hook;
    }

    /**
     * Runs a request on a copy. A database error after the copy's slot has
     * gone (its session was deleted during the request) is the copy not
     * existing, not a server error; a database locked past the busy timeout
     * is {@link WorkingCopyBusyException} (503).
     */
    <T> T guard(UUID id, Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException | TransactionException e) {
            throw translate(id, e);
        }
    }

    /**
     * A database error of a request: no copy if its slot has gone, {@link WorkingCopyBusyException} if the
     * database stayed locked past the busy timeout (the client may retry), else the error itself.
     */
    private RuntimeException translate(UUID id, RuntimeException e) {
        if (storage.existingFile(id, SLOT.WORKINGCOPY, DB_FILE).isEmpty()) {
            return new NoSuchWorkingCopyException();
        }
        if (WorkingCopyBusyException.isBusy(e)) {
            return new WorkingCopyBusyException(e);
        }
        return e;
    }

    @FunctionalInterface
    private interface IoWork {

        void run() throws IOException;
    }

    private void guardIo(UUID id, IoWork work) throws IOException {
        try {
            work.run();
        } catch (DataAccessException | TransactionException e) {
            throw translate(id, e);
        }
    }

    private static void requireRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative.");
        }
    }

    private Object lock(UUID id) {
        return locks[Math.floorMod(id.hashCode(), STRIPES)];
    }

    private void deleteSlotQuietly(UUID id) {
        try {
            storage.delete(id, SLOT.WORKINGCOPY);
        } catch (RuntimeException e) {
            log.warn("Could not delete the incomplete working copy of validation {}", id, e);
        }
    }

    private static Path toTempFile(InputStream content, String suffix) throws IOException {
        try (InputStream in = content) {
            Path file = Files.createTempFile("mztab-workingcopy-", suffix);
            try {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                return file;
            } catch (IOException | RuntimeException e) {
                SynchronousEngineService.deleteQuietly(file);
                throw e;
            }
        }
    }
}
