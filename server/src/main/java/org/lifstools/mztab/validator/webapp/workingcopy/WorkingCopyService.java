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

import static org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyRuns.ofKind;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.lifstools.mztab.validator.engine.api.EngineErrorCode;
import org.lifstools.mztab.validator.engine.api.FieldRule;
import org.lifstools.mztab.validator.engine.api.FieldRules;
import org.lifstools.mztab.validator.engine.api.MzTabVersionDetector;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.engine.api.SkeletonRequest;
import org.lifstools.mztab.validator.engine.api.ValidationLevel;
import org.lifstools.mztab.validator.engine.api.ValidationMessage;
import org.lifstools.mztab.validator.webapp.engine.DeclaredVersions;
import org.lifstools.mztab.validator.webapp.engine.EngineBusyException;
import org.lifstools.mztab.validator.webapp.engine.EngineClient;
import org.lifstools.mztab.validator.webapp.engine.EngineMessages;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry.RouteDecision;
import org.lifstools.mztab.validator.webapp.engine.EngineRequestException;
import org.lifstools.mztab.validator.webapp.engine.EngineResultReader;
import org.lifstools.mztab.validator.webapp.engine.EngineUnavailableException;
import org.lifstools.mztab.validator.webapp.engine.EnginesProperties;
import org.lifstools.mztab.validator.webapp.guidance.GuidanceResolver;
import org.lifstools.mztab.validator.webapp.guidance.ResolutionReport;
import org.lifstools.mztab.validator.webapp.service.SessionIdGenerator;
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
 * Working copies of uploaded files and new files: creation from a validation
 * session or from the engine's skeleton ({@link #createNew}), edits with undo
 * and redo ({@link WorkingCopyEditor}), the validation loop, the rules still
 * missing ({@link #stillMissing}) and the downloads. A copy lives in the
 * {@code WORKINGCOPY} slot of its id's storage directory as one SQLite file
 * ({@link WorkingCopyDb}). An upload copy's id is its session's id, so each
 * session has at most one copy, and deleting the session deletes it. A new file
 * has no session: its directory holds only the copy, and its settings
 * ({@link CopySettings}) are in the copy's meta. The storage maintenance deletes
 * either by the age of the directory.
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
 * <b>The validation loop</b> ({@link WorkingCopyRuns}). A run assembles the text of one revision (the
 * revision and the lines come from one snapshot), detects its declared version
 * as an upload's is, and sends it to that version's engine with the copy's
 * level, error limit and, for a semantic run, profile ({@link #settings}). It runs on
 * {@code workingCopyRunExecutor} (its own bounded pool and queue, {@code workingcopy.runs.threads} and
 * {@code workingcopy.runs.queue}, so that it never delays an upload's validation; a request that finds the queue
 * full is a {@link WorkingCopyBusyException}); one run per copy at a time. A request
 * during a run, and an edit, undo or redo during a run, mark it to run again
 * when it ends, on the then latest revision; the run again is skipped if it
 * would validate the same revision the same way. A busy engine is asked again
 * after its {@code Retry-After}, until {@code engines.job-timeout} after the
 * run started. The ended run, and per kind of message (structural, and the
 * CV messages of the last CV check with its revision) the messages for the
 * next comparison, are stored in the copy's meta table, so they survive a
 * restart; a running run is only held in memory. A structural run keeps the CV
 * messages of the last CV check ({@link MessageDiff#compareRun}), and a CV
 * check asked for is never replaced by a structural request. Each copy has
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
    /** Free capacity needed to create a copy, as a multiple of the upload size. */
    static final int CREATE_OVERHEAD = 3;
    static final long USAGE_MAX_AGE_SECONDS = 5;
    private static final int STRIPES = 64;
    private static final int FACTS_CACHE_SIZE = 256;
    /** The {@code mzTab-ID} of a new file. */
    static final Pattern MZTAB_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    /** The content profiles ({@code mzTab-profile}) of mzTab-M 2.1. */
    static final List<String> CONTENT_PROFILES = List.of("M", "M+S", "M+F", "M+F+E", "M+S+F+E");
    /** The line whose files have no content profile. */
    private static final String NO_CONTENT_PROFILE_LINE = "2.0";

    private static final String META_COMPLETE = "copy.complete";
    static final String META_GENERATION = "copy.generation";
    private static final String META_FILE_NAME = "copy.fileName";
    private static final String META_ENCODING = "copy.encoding";
    private static final String META_CHANGED = "copy.changedAt";
    /** The caller's id of the last change ("" if it gave none). */
    private static final String META_LAST_CHANGE = "copy.lastChangeId";
    static final String META_VERSION = "copy.mzTabVersion";
    /** {@link CopySettings#UPLOAD} or {@link CopySettings#NEW}; absent (copies made before it) is an upload. */
    static final String META_ORIGIN = "copy.origin";
    /** A new copy's settings: the profile as JSON {@link ProfileRef}, the level, the error limit, the content profile. */
    static final String META_PROFILE = "copy.profile";
    static final String META_LEVEL = "copy.level";
    static final String META_MAX_ERRORS = "copy.maxErrors";
    static final String META_CONTENT_PROFILE = "copy.contentProfile";
    static final String META_LAST = "validation.last";
    static final String META_LAST_DONE = "validation.lastDone";
    static final String META_LAST_DONE_AT = "validation.lastDoneAt";
    static final String META_BASELINE_STRUCTURAL = "validation.baseline.structural";
    static final String META_BASELINE_SEMANTIC = "validation.baseline.semantic";
    /** The revision of the last CV check; 0 for the session's own, absent if the CV rules were never checked. */
    static final String META_CV_REVISION = "validation.cvRevision";

    /** The result of {@link #createOrFind}. */
    public record Creation(WorkingCopyInfo info, boolean created) {
    }

    /** The line count and sections of one revision, which do not change. */
    private record Facts(long lineCount, SectionsSummary sections) {
    }

    /** The generation tells a re-created copy (revision 0 again) from the one before. */
    private record FactsKey(UUID id, @Nullable String generation, long revision) {
    }

    private final StorageService storage;
    private final StorageProperties storageProperties;
    private final ValidationSessionService sessions;
    private final EngineRegistry registry;
    private final EnginesProperties engines;
    private final ValidationProfileCatalog catalog;
    private final SynchronousEngineService conversions;
    private final SessionIdGenerator ids;
    private final ObjectMapper json;
    private final long retentionDays;
    private final WorkingCopyEditor editor = new WorkingCopyEditor();
    private final Object[] locks = new Object[STRIPES];
    private final WorkingCopyRuns runs;
    private final Map<FactsKey, Facts> facts = lru();
    /** The rules still missing of a revision, which do not change either; guarded by itself. */
    private final Map<FactsKey, StillMissing> stillMissing = lru();
    /**
     * The profile of an upload copy's session, which never changes: {@link #info} names it on every answer, and
     * reading the session's whole record each time would cost more than the edit. Guarded by itself.
     */
    private final Map<UUID, ProfileRef> uploadProfiles = lru();
    /** Test hook: called in {@link #info} between its read of the running run and its read of the stored run. */
    @Nullable
    private volatile Consumer<UUID> betweenInfoReads;
    private volatile long usageBytes;
    private volatile long usageMeasuredAt;
    private volatile boolean usageKnown;

    public WorkingCopyService(StorageService storage, StorageProperties storageProperties,
        ValidationSessionService sessions, EngineRegistry registry, EngineResultReader results,
        EnginesProperties engines, ValidationProfileCatalog catalog, SynchronousEngineService conversions,
        GuidanceResolver guidance, SessionIdGenerator ids, ObjectMapper json, TaskScheduler scheduler,
        @Qualifier("workingCopyRunExecutor") Executor executor,
        @Value("${minCleanupAge:7}") long retentionDays) {
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.sessions = sessions;
        this.registry = registry;
        this.engines = engines;
        this.catalog = catalog;
        this.conversions = conversions;
        this.ids = ids;
        this.json = json;
        this.retentionDays = retentionDays;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
        // built here, not a bean of its own: it reaches the copies through this service, which owns it, so there is
        // no cycle for Spring to resolve; it is called only after this constructor has returned
        this.runs = new WorkingCopyRuns(new WorkingCopyRuns.Copies() {
            @Override
            public Optional<WorkingCopyDb> openQuietly(UUID id) {
                return WorkingCopyService.this.openQuietly(id);
            }

            @Override
            public Optional<CopySettings> settings(UUID id, WorkingCopyDb db) {
                return WorkingCopyService.this.settings(id, db);
            }
        }, storage, registry, results, engines, catalog, guidance, json, scheduler, executor);
    }

    /** A map that keeps the {@value #FACTS_CACHE_SIZE} entries used last. */
    private static <K, V> Map<K, V> lru() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > FACTS_CACHE_SIZE;
            }
        };
    }

    // ---- creation and deletion ----

    /**
     * The copy of a validation, created from its upload if there is none yet.
     * An existing copy is returned unchanged, whatever {@code encoding} says,
     * before a session is looked for: a new file ({@link #createNew}), which has
     * none, opens as any other copy.
     * A JSON upload is converted to tab separated text by the engine of its
     * version first (then read as UTF-8).
     *
     * @param encoding how the upload's bytes are decoded
     * @throws NoSuchWorkingCopyException if there is no such copy or validation
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
            Optional<WorkingCopyDb> existing = openExisting(validationId);
            if (existing.isPresent()) {
                return new Creation(info(validationId, existing.get()), false);
            }
            ValidationRecord record = sessions.find(validationId)
                .orElseThrow(() -> new NoSuchWorkingCopyException(UNKNOWN_VALIDATION));
            return new Creation(create(record, encoding), true);
        }
    }

    /**
     * A new file: a copy of the engine's skeleton for the chosen version, {@code mzTab-ID} and content profile
     * ({@link SkeletonRequest}), with its own id and no validation session. Its settings are kept in its meta: the
     * repository rules chosen, and the validator form's level and error limit ({@link CopySettings#DEFAULT_LEVEL},
     * {@link CopySettings#DEFAULT_MAX_ERRORS}). The choices are checked before the engine is asked.
     *
     * @throws NewFileRefusedException for a version whose line has no engine here, a content profile that does not
     * fit the version (none for 2.0, one of {@link #CONTENT_PROFILES} otherwise), an {@code mzTab-ID} outside
     * {@link #MZTAB_ID}, repository rules not in the catalog or that do not list the version, or a request the
     * engine refuses; nothing is stored
     * @throws StorageFullException if less than three times the skeleton's size is free
     * @throws EngineBusyException if the engine is busy
     * @throws EngineUnavailableException if the engine cannot be reached, or answers a text that does not start with
     * the version line asked for
     */
    public WorkingCopyInfo createNew(NewFile f) {
        if (f == null) {
            throw new NewFileRefusedException("The choices of the new file are required.");
        }
        String version = f.mzTabVersion() == null ? "" : f.mzTabVersion().strip();
        Optional<String> line = MzTabVersions.lineOf(version);
        Optional<EngineClient> engine = line.flatMap(registry::forLine);
        if (version.isEmpty() || engine.isEmpty()) {
            throw new NewFileRefusedException("This server has no validator for mzTab-M "
                + (version.isEmpty() ? "(no version given)" : version) + "; it offers mzTab-M "
                + String.join(" and ", registry.lines()) + ".");
        }
        String contentProfile = f.contentProfile();
        if (NO_CONTENT_PROFILE_LINE.equals(line.get())) {
            if (contentProfile != null) {
                throw new NewFileRefusedException("mzTab-M " + version + " has no content profile.");
            }
        } else if (contentProfile == null || !CONTENT_PROFILES.contains(contentProfile)) {
            throw new NewFileRefusedException("mzTab-M " + version + " needs a content profile, one of "
                + String.join(", ", CONTENT_PROFILES) + ".");
        }
        if (f.mzTabId() == null || !MZTAB_ID.matcher(f.mzTabId()).matches()) {
            throw new NewFileRefusedException("The mzTab-ID must start with a letter or a digit, followed by up to 63 "
                + "letters, digits, dots, underscores or hyphens.");
        }
        if (f.profileId() == null || f.profileId().isBlank() || f.profileVersion() == null
            || f.profileVersion().isBlank()) {
            throw new NewFileRefusedException("Choose the repository rules of the new file.");
        }
        ValidationProfile profile = catalog.find(f.profileId(), f.profileVersion())
            .orElseThrow(() -> new NewFileRefusedException("Unknown repository rules: " + f.profileId() + " "
                + f.profileVersion() + "."));
        List<String> listed = profile.mzTabVersions() == null ? List.of() : profile.mzTabVersions();
        if (!listed.isEmpty() && !listed.contains(version)) {
            throw new NewFileRefusedException("The repository rules " + profile.id() + " " + profile.version()
                + " apply to mzTab-M " + String.join(" and ", listed) + ", not to mzTab-M " + version + ".");
        }
        String skeleton;
        try {
            skeleton = engine.get().skeleton(new SkeletonRequest(version, contentProfile, f.mzTabId()));
        } catch (EngineRequestException e) {
            throw new NewFileRefusedException(e.error().message());
        }
        if (!skeleton.startsWith("MTD\tmzTab-version\t" + version + "\n")) {
            // the only line the webapp reads: a text without it is not the skeleton asked for
            log.warn("The engine for mzTab-M {} answered a skeleton without the version line of {}", line.get(),
                version);
            throw new EngineUnavailableException(line.get());
        }
        String settings;
        try {
            settings = json.writeValueAsString(ProfileRef.catalog(profile.id(), profile.version()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the repository rules of a new file", e);
        }
        byte[] text = skeleton.getBytes(StandardCharsets.UTF_8);
        UUID id = ids.generate();
        synchronized (lock(id)) {
            if (storageProperties.getMaxTotalBytes() - storage.totalBytes() < CREATE_OVERHEAD * (long) text.length) {
                throw new StorageFullException();
            }
            try {
                WorkingCopyDb db = WorkingCopyDb.open(storage.localFile(id, SLOT.WORKINGCOPY, DB_FILE));
                db.importFrom(new ByteArrayInputStream(text), SourceEncoding.UTF_8);
                db.setMeta(META_GENERATION, UUID.randomUUID().toString());
                db.setMeta(META_FILE_NAME, f.mzTabId() + ".mzTab");
                db.setMeta(META_ENCODING, SourceEncoding.UTF_8.name());
                db.setMeta(META_VERSION, version);
                db.setMeta(META_CHANGED, Instant.now().toString());
                db.setMeta(META_ORIGIN, CopySettings.NEW);
                db.setMeta(META_PROFILE, settings);
                db.setMeta(META_LEVEL, CopySettings.DEFAULT_LEVEL.name());
                db.setMeta(META_MAX_ERRORS, Integer.toString(CopySettings.DEFAULT_MAX_ERRORS));
                db.setMeta(META_CONTENT_PROFILE, contentProfile == null ? "" : contentProfile);
                db.setMeta(META_COMPLETE, "true");
                storage.touch(id);
                return info(id, db);
            } catch (RuntimeException | IOException e) {
                // nothing else is in the directory of a new file
                try {
                    storage.deleteAll(id);
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
                if (e instanceof IOException io) {
                    throw new UncheckedIOException("Could not create the new file " + id, io);
                }
                throw (RuntimeException) e;
            }
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
                    // the first run is compared with the session's messages; the session's CV messages, if it
                    // checked the CV rules, are those of revision 0
                    db.setMeta(META_BASELINE_STRUCTURAL, json.writeValueAsString(ofKind(record.messages(), false)));
                    // the engine says when it did not check them (skipped, or the check broke)
                    if (record.validationProfile() != null
                        && record.validationProfile().kind() != ProfileRef.Kind.NONE
                        && record.messages().stream().noneMatch(MessageDiff::isCvNotChecked)) {
                        db.setMeta(META_BASELINE_SEMANTIC, json.writeValueAsString(ofKind(record.messages(), true)));
                        db.setMeta(META_CV_REVISION, "0");
                    }
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
            runs.remove(id);
            storage.delete(id, SLOT.WORKINGCOPY);
            synchronized (facts) {
                facts.keySet().removeIf(k -> k.id().equals(id));
            }
            synchronized (stillMissing) {
                stillMissing.keySet().removeIf(k -> k.id().equals(id));
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
     * The problems of the copy's last finished check as Markdown, with the line numbers of the revision that run
     * validated (taken from the stored run, not from {@link #info}, which labels the messages of a run still
     * running with that run's revision). A check that is not current still gives a report that says so.
     *
     * @throws NoSuchWorkingCopyException if there is no copy
     * @throws WorkingCopyConflictException if no check of the copy has ever finished with a result. If the latest
     * check gave none but an earlier one did, the report is the earlier one's and says so.
     */
    public String resolutionReport(UUID id) {
        return guard(id, () -> {
            WorkingCopyDb db = db(id);
            // one snapshot that takes no write lock (as info's): the runs, the check time, the CV check and the
            // revision belong together, and a writer holding the copy does not hold up the report
            String[] stored = db.snapshotMeta(META_LAST, META_LAST_DONE, META_LAST_DONE_AT, META_CV_REVISION,
                WorkingCopyDb.REVISION_KEY, META_VERSION, META_FILE_NAME);
            long current = stored[4] == null ? 0L : Long.parseLong(stored[4]);
            WorkingCopyValidation latest = parseValidation(stored[0]);
            WorkingCopyValidation finished = finishedRun(latest, stored[1]);
            if (finished == null) {
                throw new WorkingCopyConflictException(latest == null
                    ? "The copy has not been checked yet. Check it first."
                    : "No check of the copy has given a result yet. Check it again.");
            }
            boolean failed = latest != null && latest.status() == Status.FAILED;
            boolean running = runs.isRunning(id);
            String version = stored[5];
            ProfileRef profile = settings(id, db).map(CopySettings::profile).orElse(null);
            return ResolutionReport.markdown(new ResolutionReport.CopyCheck(stored[6],
                version == null || version.isEmpty() ? null : version, finished.revision(), current,
                stored[2] == null ? null : Instant.parse(stored[2]), finished.semantic(), profile, running,
                finished.parsed() ? null : finished.readUpTo(), failed ? latest.revision() : null,
                failed ? latest.failureReason() : null, stored[3] == null ? null : Long.valueOf(stored[3])),
                finished.guidance());
        });
    }

    /**
     * The run whose report the copy serves: the stored finished run, else the latest if it is DONE (a copy whose run
     * was stored before the finished run had a key of its own); null if there is none. Shared by the report and
     * {@code info}'s {@code reportRevision}, so the two agree.
     */
    private WorkingCopyValidation finishedRun(@Nullable WorkingCopyValidation latest, @Nullable String storedDone) {
        WorkingCopyValidation finished = parseValidation(storedDone);
        if (finished == null && latest != null && latest.status() == Status.DONE) {
            finished = latest;
        }
        return finished;
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
        return info(id, db, null);
    }

    /**
     * What a change committed: its revision, the layout revision and its change id as of that commit. The answer to
     * the change carries them (not re-read: another change may have committed since).
     */
    private record Committed(long revision, long layoutRevision, @Nullable UUID changeId) {

        /** Read inside the transaction of the change, after it. */
        static Committed of(WorkingCopyDb db, long revision, @Nullable UUID changeId) {
            String layout = db.meta(WorkingCopyEditor.LAYOUT_REVISION_KEY);
            return new Committed(revision, layout == null ? 0L : Long.parseLong(layout), changeId);
        }
    }

    /**
     * @param committed what a change of this call committed, which the answer to that change carries, or null to
     * read the copy as it is. The other values (the validation, undo and redo, the sizes) are read after the commit,
     * so they may be of a later change.
     */
    private WorkingCopyInfo info(UUID id, WorkingCopyDb db, @Nullable Committed committed) {
        // the running run before the stored one: a run that ends between the two reads is then shown as running
        // (with the result it stored), never as no validation at all (stored run read before it was stored, running
        // run read after it was removed)
        WorkingCopyRuns.Running run = runs.snapshot(id);
        boolean running = run != null;
        long runRevision = run == null ? -1 : run.revision();
        boolean runSemantic = run != null && run.semantic();
        Consumer<UUID> hook = betweenInfoReads;
        if (hook != null) {
            hook.accept(id);
        }
        // one snapshot read that takes no write lock (the answer to a committed change must not wait for another
        // writer): the revision, the last change's id and the rest belong together, so a reader never pairs the
        // id of a change with the revision before it. A CV check stores its two values together as well.
        String[] stored = db.snapshotMeta(META_LAST, META_CV_REVISION, WorkingCopyEditor.LAYOUT_REVISION_KEY,
            META_LAST_CHANGE, WorkingCopyDb.REVISION_KEY, META_GENERATION, META_ORIGIN, META_PROFILE, META_LAST_DONE);
        // the revision before the facts: facts read after it may be newer, never older (see facts)
        long revision = committed != null ? committed.revision()
            : (stored[4] == null ? 0L : Long.parseLong(stored[4]));
        Facts f = facts(id, db, revision);
        boolean canUndo = editor.canUndo(db);
        boolean canRedo = editor.canRedo(db);
        WorkingCopyValidation last = parseValidation(stored[0]);
        WorkingCopyValidation finishedRun = finishedRun(last, stored[8]);
        WorkingCopyValidation shown = last;
        if (running) {
            long validated = runRevision >= 0 ? runRevision : revision;
            shown = last == null ? WorkingCopyValidation.firstRunning(validated, runSemantic)
                : last.running(validated, runSemantic);
        }
        if (shown != null) {
            // the messages are those of the stored run (last), whatever run is running now; a stored run has ended,
            // so the revision it validated is the one its line numbers are of (never the running run's)
            shown = shown.withMessagesRevision(last != null ? last.revision() : shown.revision())
                .withStale(shown.revision() != revision);
        }
        String changed = db.meta(META_CHANGED);
        Instant expiresAt = (changed == null ? Instant.now() : Instant.parse(changed))
            .plus(Duration.ofDays(retentionDays));
        String version = db.meta(META_VERSION);
        boolean isNew = CopySettings.NEW.equals(stored[6]);
        ProfileRef profile = isNew ? readProfile(id, stored[7]) : uploadProfile(id);
        return new WorkingCopyInfo(id, db.meta(META_FILE_NAME), revision,
            version == null || version.isEmpty() ? null : version, f.lineCount(), f.sections(), canUndo, canRedo,
            expiresAt, shown, stored[1] == null ? null : Long.valueOf(stored[1]),
            committed != null ? committed.layoutRevision() : stored[2] == null ? 0L : Long.parseLong(stored[2]),
            committed != null ? committed.changeId()
                : stored[3] == null || stored[3].isEmpty() ? null : UUID.fromString(stored[3]),
            stored[5] == null || stored[5].isEmpty() ? null : UUID.fromString(stored[5]),
            isNew ? CopySettings.NEW : CopySettings.UPLOAD, profile,
            finishedRun == null ? null : finishedRun.revision());
    }

    /**
     * How the copy is validated: a new copy's settings from its meta, an upload copy's from its session.
     *
     * @return empty for an upload copy whose session is gone (being deleted)
     * @throws IllegalStateException if a new copy's stored settings cannot be read
     */
    public Optional<CopySettings> settings(UUID id, WorkingCopyDb db) {
        String[] stored = db.snapshotMeta(META_ORIGIN, META_PROFILE, META_LEVEL, META_MAX_ERRORS);
        if (!CopySettings.NEW.equals(stored[0])) {
            return sessions.find(id).map(r -> new CopySettings(r.validationProfile(), r.level(), r.maxErrors(),
                CopySettings.UPLOAD));
        }
        try {
            return Optional.of(new CopySettings(readProfile(id, stored[1]), ValidationLevel.valueOf(stored[2]),
                Integer.parseInt(stored[3]), CopySettings.NEW));
        } catch (RuntimeException e) {
            throw new IllegalStateException("The settings of working copy " + id + " cannot be read", e);
        }
    }

    private ProfileRef readProfile(UUID id, @Nullable String stored) {
        if (stored == null) {
            throw new IllegalStateException("Working copy " + id + " has no repository rules");
        }
        try {
            return json.readValue(stored, ProfileRef.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The repository rules of working copy " + id + " cannot be read", e);
        }
    }

    /** The profile of an upload copy's session, cached; null if the session is gone. */
    @Nullable
    private ProfileRef uploadProfile(UUID id) {
        synchronized (uploadProfiles) {
            ProfileRef cached = uploadProfiles.get(id);
            if (cached != null) {
                return cached;
            }
        }
        ProfileRef profile = sessions.find(id).map(ValidationRecord::validationProfile).orElse(null);
        if (profile != null) {
            synchronized (uploadProfiles) {
                uploadProfiles.put(id, profile);
            }
        }
        return profile;
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
        if (batch == null) {
            throw new IllegalArgumentException("A batch needs at least one operation");
        }
        requireRevision(batch.revision());
        // 400 before anything that could answer 503 or 409: the batch is wrong whatever the copy's state
        WorkingCopyEditor.requireWellFormedText(batch);
        return guard(id, () -> {
            WorkingCopyDb db = db(id);
            requireCapacity();
            // the retention stamp is written in the edit's own transaction: it commits with the edit or not at all
            Committed committed = db.inTransaction(() -> {
                requireGeneration(db, batch.generation());
                long next = editor.apply(db, batch);
                stamp(db, batch.changeId());
                return Committed.of(db, next, batch.changeId());
            });
            changed(id, db, committed.revision());
            return info(id, db, committed);
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
        return undo(id, revision, null);
    }

    /** As {@link #undo(UUID, long)}; {@code changeId} is kept as {@link WorkingCopyInfo#lastChangeId}. */
    public WorkingCopyInfo undo(UUID id, long revision, UUID changeId) {
        return undo(id, revision, changeId, null);
    }

    /** As {@link #undo(UUID, long, UUID)}; a {@code generation} other than the copy's is refused (409). */
    public WorkingCopyInfo undo(UUID id, long revision, UUID changeId, UUID generation) {
        return undoOrRedo(id, revision, true, changeId, generation);
    }

    /** Re-applies the change undone last; as {@link #undo}. */
    public WorkingCopyInfo redo(UUID id, long revision) {
        return redo(id, revision, null);
    }

    /** As {@link #redo(UUID, long)}; {@code changeId} is kept as {@link WorkingCopyInfo#lastChangeId}. */
    public WorkingCopyInfo redo(UUID id, long revision, UUID changeId) {
        return redo(id, revision, changeId, null);
    }

    /** As {@link #redo(UUID, long, UUID)}; a {@code generation} other than the copy's is refused (409). */
    public WorkingCopyInfo redo(UUID id, long revision, UUID changeId, UUID generation) {
        return undoOrRedo(id, revision, false, changeId, generation);
    }

    private WorkingCopyInfo undoOrRedo(UUID id, long revision, boolean undo, UUID changeId, UUID generation) {
        requireRevision(revision);
        return guard(id, () -> undoOrRedo(id, db(id), revision, undo, changeId, generation));
    }

    private WorkingCopyInfo undoOrRedo(UUID id, WorkingCopyDb db, long revision, boolean undo, UUID changeId,
        UUID generation) {
        requireCapacity();
        // one transaction, so "nothing to do" and the change see the same state
        Committed committed = db.inTransaction(() -> {
            requireGeneration(db, generation);
            long current = db.revision();
            if (current != revision) {
                throw new StaleRevisionException(current, revision);
            }
            if (undo ? !editor.canUndo(db) : !editor.canRedo(db)) {
                throw new NothingToUndoException(undo);
            }
            long applied = undo ? editor.undo(db, revision) : editor.redo(db, revision);
            stamp(db, changeId);
            return Committed.of(db, applied, changeId);
        });
        changed(id, db, committed.revision());
        return info(id, db, committed);
    }

    /**
     * A copy deleted and created again restarts at revision 0, so a client's revision of the old copy may equal the
     * new one's: a generation the client names must be the copy's. Inside the transaction of the change.
     */
    private static void requireGeneration(WorkingCopyDb db, @Nullable UUID generation) {
        if (generation != null && !generation.toString().equals(db.meta(META_GENERATION))) {
            throw new CopyReplacedException();
        }
    }

    /** Restarts retention; inside the transaction of the change. */
    private void stamp(WorkingCopyDb db, UUID changeId) {
        db.setMeta(META_CHANGED, Instant.now().toString());
        db.setMeta(META_LAST_CHANGE, changeId == null ? "" : changeId.toString());
    }

    /**
     * What follows a committed change: restarts retention and marks a running validation to run again. The change is
     * committed, so nothing here may fail the call (the caller would report a saved edit as failed); a failing step
     * is logged.
     */
    private void changed(UUID id, WorkingCopyDb db, long revision) {
        try {
            storage.touch(id);
        } catch (RuntimeException e) {
            log.warn("Could not restart the retention of working copy {} after revision {}", id, revision, e);
        }
        runs.markChanged(id, revision);
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
        runs.requestValidation(id, db, semantic);
        return info(id, db);
    }

    @Nullable
    private WorkingCopyValidation lastValidation(WorkingCopyDb db) {
        return parseValidation(db.meta(META_LAST));
    }

    @Nullable
    private WorkingCopyValidation parseValidation(@Nullable String stored) {
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

    // ---- still missing ----

    /** The requirement levels "still missing" lists, in its order. */
    private static final List<String> MISSING_LEVELS = List.of("MUST", "SHOULD");

    /**
     * The MUST and SHOULD rules of the copy's repository rules that the text of its current revision does not meet
     * yet. The engine of the text's version decides it from the field rules of the profile's mapping: the copy's
     * catalog profile, an upload's custom mapping, or the line's default profile for a copy without one. A
     * revision's result is cached, as its text never changes.
     *
     * @throws NoSuchWorkingCopyException if there is no copy
     * @throws WorkingCopyConflictException if no engine here serves the text's version, the copy's catalog profile
     * is of another version line, or the engine refuses the mapping
     * @throws TooLargeException if the text is larger than the engine takes
     * @throws EngineBusyException if the engine is busy
     * @throws EngineUnavailableException if the engine cannot be reached
     */
    public StillMissing stillMissing(UUID id) {
        return guard(id, () -> {
            try {
                return stillMissing(id, db(id));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not check the rules of working copy " + id, e);
            }
        });
    }

    private StillMissing stillMissing(UUID id, WorkingCopyDb db) throws IOException {
        String[] current = db.snapshotMeta(META_GENERATION, WorkingCopyDb.REVISION_KEY);
        synchronized (stillMissing) {
            StillMissing cached = stillMissing.get(new FactsKey(id, current[0],
                current[1] == null ? 0L : Long.parseLong(current[1])));
            if (cached != null) {
                return cached;
            }
        }
        Path text = Files.createTempFile("mztab-workingcopy-", ".mztab");
        Path mapping = null;
        try {
            WorkingCopyDb.Snapshot written;
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(text))) {
                written = db.writeRevisionTo(out, META_GENERATION);
            }
            RouteDecision decision = registry.route(DeclaredVersions.ofTsv(text));
            Optional<String> rejection = EngineMessages.rejection(decision, engines.unavailableHintUrl());
            if (rejection.isPresent()) {
                throw new WorkingCopyConflictException(rejection.get());
            }
            RouteDecision.Routed routed = (RouteDecision.Routed) decision;
            ProfileRef profile = settings(id, db).map(CopySettings::profile).orElse(null);
            Optional<String> mismatch = runs.profileMismatch(profile, routed.line());
            if (mismatch.isPresent()) {
                throw new WorkingCopyConflictException(mismatch.get());
            }
            mapping = runs.mappingFile(id, profile, routed.line(), new ArrayList<>());
            StillMissing result;
            if (mapping == null) {
                // no rules for the line: nothing can be missing
                result = new StillMissing(written.revision(), true, List.of());
            } else {
                try {
                    result = unmet(written.revision(), routed.engine().fieldRules(mapping, text));
                } catch (EngineRequestException e) {
                    if (e.error().code() == EngineErrorCode.FILE_TOO_LARGE) {
                        throw new TooLargeException(e.error().message());
                    }
                    throw new WorkingCopyConflictException(e.error().message());
                }
            }
            synchronized (stillMissing) {
                stillMissing.put(new FactsKey(id, written.meta()[0], written.revision()), result);
            }
            return result;
        } finally {
            SynchronousEngineService.deleteQuietly(text);
            SynchronousEngineService.deleteQuietly(mapping);
        }
    }

    /**
     * The MUST rules, then the SHOULD rules, that {@code rules} says are absent. A text the engine could not read
     * into a model was not checked: every MUST and SHOULD rule is listed, its presence unknown. A rule whose
     * presence could not be decided in a read text ({@code present} null) is not listed as missing.
     */
    static StillMissing unmet(long revision, FieldRules rules) {
        List<FieldRule> missing = new ArrayList<>();
        for (String level : MISSING_LEVELS) {
            for (FieldRule rule : rules.rules()) {
                if (level.equals(rule.requirement()) && (!rules.parsed() || Boolean.FALSE.equals(rule.present()))) {
                    missing.add(rule);
                }
            }
        }
        return new StillMissing(revision, rules.parsed(), missing);
    }

    // ---- downloads ----

    private static final LongConsumer NO_REVISION = revision -> {
    };

    /** Writes the current text, byte for byte as it is stored. */
    public void writeTsv(UUID id, OutputStream out) throws IOException {
        writeTsv(id, out, NO_REVISION);
    }

    /** As {@link #writeTsv(UUID, OutputStream)}; {@code revision} is told the revision of the text before its first byte. */
    public void writeTsv(UUID id, OutputStream out, LongConsumer revision) throws IOException {
        guardIo(id, () -> db(id).writeTo(out, revision));
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
        writeJson(id, out, NO_REVISION);
    }

    /** As {@link #writeJson(UUID, OutputStream)}; {@code revision} is told the revision of the converted text before the first byte. */
    public void writeJson(UUID id, OutputStream out, LongConsumer revision) throws IOException {
        guardIo(id, () -> writeJson(id, db(id), out, revision));
    }

    private void writeJson(UUID id, WorkingCopyDb db, OutputStream out, LongConsumer revisionKnown) throws IOException {
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
            revisionKnown.accept(revision);
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

    /** For tests only: see {@link #betweenInfoReads}. */
    void betweenInfoReads(@Nullable Consumer<UUID> hook) {
        this.betweenInfoReads = hook;
    }

    /** For tests only: see {@link WorkingCopyRuns#retryGate}. */
    void retryGate(@Nullable Consumer<UUID> hook) {
        runs.retryGate(hook);
    }

    /** For tests only: see {@link WorkingCopyRuns#retryDeferred}. */
    void retryDeferred(@Nullable Consumer<UUID> hook) {
        runs.retryDeferred(hook);
    }

    /** For tests only: see {@link WorkingCopyRuns#beforeRunEnds}. */
    void beforeRunEnds(@Nullable Consumer<UUID> hook) {
        runs.beforeRunEnds(hook);
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

    static Path toTempFile(InputStream content, String suffix) throws IOException {
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
