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
package org.lifstools.mztab.validator.webapp.imports;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.lifstools.mztab.submission.mapping.Mapping;
import org.lifstools.mztab.validator.engine.api.EngineErrorCode;
import org.lifstools.mztab.validator.engine.api.EngineInfo;
import org.lifstools.mztab.validator.engine.api.MappingApplyRequest;
import org.lifstools.mztab.validator.engine.api.MappingApplyResult;
import org.lifstools.mztab.validator.engine.api.MzTabVersions;
import org.lifstools.mztab.validator.webapp.engine.EngineClient;
import org.lifstools.mztab.validator.webapp.engine.EngineRegistry;
import org.lifstools.mztab.validator.webapp.engine.EngineRequestException;
import org.lifstools.mztab.validator.webapp.engine.EngineUnavailableException;
import org.lifstools.mztab.validator.webapp.workingcopy.EditBatch;
import org.lifstools.mztab.validator.webapp.workingcopy.EditOperation;
import org.lifstools.mztab.validator.webapp.workingcopy.LineEdits;
import org.lifstools.mztab.validator.webapp.workingcopy.StaleRevisionException;
import org.lifstools.mztab.validator.webapp.workingcopy.TooLargeException;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyDb;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyInfo;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService;
import org.springframework.stereotype.Service;

/**
 * Previews an import against a working copy: the copy's output mapping runs in the engine on the copy's text, and
 * the result comes back as an edit batch that the caller applies with the edits endpoint. Nothing is stored here and
 * the copy does not change.
 */
@Service
public class ImportService {

    /** The only kind of import: a vocabulary instance (JSON). */
    public static final String VOCABULARY_INSTANCE = "vocabulary-instance";

    private final WorkingCopyService copies;
    private final EngineRegistry registry;
    private final OutputMappings mappings;

    public ImportService(WorkingCopyService copies, EngineRegistry registry, OutputMappings mappings) {
        this.copies = copies;
        this.registry = registry;
        this.mappings = mappings;
    }

    /**
     * @return the kinds of import the copy takes: empty if no output mapping targets the copy's version
     * @throws org.lifstools.mztab.validator.webapp.workingcopy.NoSuchWorkingCopyException if there is no copy
     */
    public List<String> kinds(UUID id) {
        return mappings.forVersion(copies.info(id).mzTabVersion()).isPresent()
            ? List.of(VOCABULARY_INSTANCE) : List.of();
    }

    /**
     * @throws IllegalArgumentException (400) for an unknown kind, no output mapping for the copy's version, an
     * instance without datasets, a dataset index out of range or missing, no study id, or a refusal of the engine
     * @throws StaleRevisionException (409) if the copy is not at the request's revision
     * @throws TooLargeException if the copy's text is over the engine's upload limit, or the engine answers that the
     * request (the text and the instance) is too large
     * @throws org.lifstools.mztab.validator.webapp.engine.EngineBusyException (503)
     * @throws EngineUnavailableException (503)
     */
    public ImportPreview preview(UUID id, ImportRequest request) {
        if (!VOCABULARY_INSTANCE.equals(request.kind())) {
            throw new IllegalArgumentException("kind must be " + VOCABULARY_INSTANCE + ".");
        }
        WorkingCopyInfo info = copies.info(id);
        String version = info.mzTabVersion();
        Mapping mapping = mappings.forVersion(version).orElseThrow(() -> new IllegalArgumentException(
            "No output mapping for mzTab-M " + (version == null ? "of unknown version" : version) + " yet"));
        if (request.instance() == null || request.instance().isNull()) {
            throw new IllegalArgumentException("instance is required.");
        }
        if (request.studyId() == null || request.studyId().isBlank()) {
            throw new IllegalArgumentException("studyId is required.");
        }
        JsonNode dataset = dataset(request);
        int index = request.datasetIndex() != null ? request.datasetIndex() : 1;
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("study_id", request.studyId());
        JsonNode datasetId = dataset.get("dataset_id");
        if (datasetId != null && datasetId.isValueNode() && !datasetId.isNull()) {
            parameters.put("dataset_id", datasetId.asText());
        }
        parameters.put("dataset_index", Integer.toString(index));

        String line = MzTabVersions.lineOf(version).orElseThrow(() -> new IllegalArgumentException(
            "No output mapping for mzTab-M " + version + " yet"));
        EngineClient engine = registry.forLine(line).orElseThrow(() -> new EngineUnavailableException(line));
        long limit = registry.lastKnownInfo().getOrDefault(line, Optional.empty())
            .or(() -> registry.refresh(line)).map(EngineInfo::maxUploadBytes)
            .orElseThrow(() -> new EngineUnavailableException(line));

        // text and revision from one snapshot, capped as it is written
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        long revision = copies.read(id, db -> snapshot(db, text, limit));
        if (revision != request.revision()) {
            throw new StaleRevisionException(revision, request.revision());
        }
        MappingApplyResult result;
        try {
            result = engine.applyMapping(new MappingApplyRequest(mapping.id(), mapping.version(), parameters,
                request.instance(), text.toString(StandardCharsets.UTF_8), true, null, request.keepExisting()));
        } catch (EngineRequestException e) {
            if (e.error().code() == EngineErrorCode.FILE_TOO_LARGE) {
                // the request's JSON body (the copy's text and the instance) is over the engine's limit
                throw new TooLargeException(e.error().message());
            }
            throw new IllegalArgumentException(e.error().message());
        }
        // result.text() (the engine's rendering of the result) is not used: the edits are all the caller applies
        List<EditOperation> operations = result.edits().stream().map(LineEdits::toOperation).toList();
        return new ImportPreview(request.kind(), revision, mapping.id(), mapping.version(), result.suggestions(),
            result.notes(), new EditBatch(revision, operations));
    }

    /** The selected dataset of the instance; checks the index. */
    private static JsonNode dataset(ImportRequest request) {
        JsonNode datasets = request.instance().get("datasets");
        int n = datasets != null && datasets.isArray() ? datasets.size() : 0;
        if (n == 0) {
            throw new IllegalArgumentException("The instance has no datasets.");
        }
        Integer index = request.datasetIndex();
        if (index == null) {
            if (n > 1) {
                throw new IllegalArgumentException("choose one of " + n + " datasets");
            }
            index = 1;
        }
        if (index < 1 || index > n) {
            throw new IllegalArgumentException("dataset " + index + " of " + n);
        }
        return datasets.get(index - 1);
    }

    private static long snapshot(WorkingCopyDb db, ByteArrayOutputStream text, long limit) {
        try {
            return db.writeRevisionTo(new Capped(text, limit));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes up to {@code limit} bytes; more is {@link TooLargeException}. */
    private static final class Capped extends OutputStream {

        private final ByteArrayOutputStream out;
        private final long limit;

        Capped(ByteArrayOutputStream out, long limit) {
            this.out = out;
            this.limit = limit;
        }

        private void room(int n) {
            if (out.size() + (long) n > limit) {
                throw new TooLargeException("The copy's text is larger than the engine takes (" + limit
                    + " bytes), so nothing can be imported into it.");
            }
        }

        @Override
        public void write(int b) {
            room(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            room(len);
            out.write(b, off, len);
        }
    }
}
