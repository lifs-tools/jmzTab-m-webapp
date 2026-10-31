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
package org.lifstools.mztab2.server.api.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.lifstools.mztab.validator.webapp.sections.MetadataGroup;
import org.lifstools.mztab.validator.webapp.sections.MetadataPage;
import org.lifstools.mztab.validator.webapp.sections.Section;
import org.lifstools.mztab.validator.webapp.sections.TablePage;
import org.lifstools.mztab.validator.webapp.sections.TableQuery;
import org.lifstools.mztab.validator.webapp.workingcopy.EditBatch;
import org.lifstools.mztab.validator.webapp.workingcopy.Line;
import org.lifstools.mztab.validator.webapp.workingcopy.NoSuchWorkingCopyException;
import org.lifstools.mztab.validator.webapp.workingcopy.SourceEncoding;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyInfo;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyReader;
import org.lifstools.mztab.validator.webapp.workingcopy.WorkingCopyService;
import org.lifstools.mztab.validator.webapp.workingcopy.quickfix.QuickFixPreview;
import org.lifstools.mztab.validator.webapp.workingcopy.quickfix.QuickFixService;
import org.lifstools.mztab2.server.api.RequestBodies;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Working copies of validated files: the editor's REST API. A copy is found
 * only by its id, which is the id of the validation it was made from; there is
 * no listing. Every parameter is checked and out-of-range values are refused
 * with 400, never clamped: ids must be UUIDs, line ranges 1 to 1,000 lines
 * from line 1 to the line count + 1, pages as in the validation endpoints.
 */
@RestController
@RequestMapping("/rest/v2/working-copies")
@Tag(name = "working-copies")
public class WorkingCopiesApiController {

    static final String TSV = "text/tab-separated-values";

    private final WorkingCopyService copies;
    private final WorkingCopyReader reader;
    private final QuickFixService quickFixes;
    private final RequestBodies bodies;
    private final ObjectMapper json;

    public WorkingCopiesApiController(WorkingCopyService copies, WorkingCopyReader reader, QuickFixService quickFixes,
        RequestBodies bodies, ObjectMapper json) {
        this.copies = copies;
        this.reader = reader;
        this.quickFixes = quickFixes;
        this.bodies = bodies;
        this.json = json;
    }

    @Operation(operationId = "createWorkingCopy", summary = "Creates the working copy of a validated file, or "
        + "returns the existing one (201 for a new copy, 200 for an existing one)")
    @ApiResponse(responseCode = "201", description = "A new copy", content = @Content(
        mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WorkingCopyInfo.class)))
    @ApiResponse(responseCode = "200", description = "The existing copy, unchanged", content = @Content(
        mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WorkingCopyInfo.class)))
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkingCopyInfo> createWorkingCopy(
        @Parameter(description = "The id of the validation whose upload is copied")
        @RequestParam("validation") String validation,
        @Parameter(description = "How the upload's bytes are decoded; a file that is not UTF-8 is refused with 422 "
            + "unless WINDOWS_1252 or ISO_8859_1 is chosen. Ignored for an existing copy and for JSON uploads.")
        @RequestParam(value = "encoding", defaultValue = "UTF_8") SourceEncoding encoding) {
        UUID id = parse(validation, "validation");
        WorkingCopyService.Creation creation = copies.createOrFind(id, encoding);
        if (!creation.created()) {
            return ResponseEntity.ok(creation.info());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri().path("/{id}").buildAndExpand(id).toUri();
        return ResponseEntity.created(location).body(creation.info());
    }

    @Operation(operationId = "getWorkingCopy", summary = "Returns a working copy: revision, version, counts and "
        + "its validation")
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkingCopyInfo getWorkingCopy(@PathVariable("id") String id) {
        return copies.info(parse(id));
    }

    @Operation(operationId = "deleteWorkingCopy", summary = "Deletes a working copy; its validation stays")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorkingCopy(@PathVariable("id") String id) {
        copies.delete(parse(id));
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "editWorkingCopy", summary = "Applies a batch of edits to the named revision; a stale "
        + "revision is refused with 409",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = EditBatch.class))))
    @PostMapping(value = "/{id}/edits", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkingCopyInfo editWorkingCopy(@PathVariable("id") String id,
        @Parameter(hidden = true) HttpServletRequest request) throws IOException {
        UUID uuid = parse(id);
        EditBatch batch;
        // the body is capped at the upload limit before it is read
        try (InputStream in = bodies.open(request)) {
            batch = json.readValue(in, EditBatch.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The edit batch could not be read"
                + (e.getLocation() == null ? "" : " at line " + e.getLocation().getLineNr() + ", column "
                + e.getLocation().getColumnNr()) + ": it must be JSON with a revision and known operations.");
        }
        if (batch == null) {
            throw new IllegalArgumentException("An edit batch is required.");
        }
        return copies.edit(uuid, batch);
    }

    @Operation(operationId = "previewWorkingCopyQuickFix", summary = "Previews a quick fix for a message of the "
        + "copy's last validation without changing the copy; post the batch to the edits endpoint to apply it. 409 "
        + "unless the last validation is finished, of the current revision and not stale; 400 if the fix does not "
        + "apply to the message, cannot be applied to a group, would need more than 5000 operations, or is not a "
        + "text edit (reencode-latin1: re-create the copy with encoding=WINDOWS_1252)")
    @GetMapping(value = "/{id}/quick-fixes/{fixId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public QuickFixPreview previewWorkingCopyQuickFix(@PathVariable("id") String id,
        @PathVariable("fixId") String fixId,
        @Parameter(description = "An index into the messages of the copy's last validation")
        @RequestParam("message") int message,
        @Parameter(description = "Whether to fix every message of the same guidance group")
        @RequestParam(value = "all", defaultValue = "false") boolean all) {
        return quickFixes.preview(parse(id), fixId, message, all);
    }

    @Operation(operationId = "undoWorkingCopy", summary = "Reverts the newest change; 409 if the revision is stale "
        + "or there is nothing to undo")
    @PostMapping(value = "/{id}/undo", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkingCopyInfo undoWorkingCopy(@PathVariable("id") String id,
        @Parameter(description = "The copy's current revision") @RequestParam("revision") long revision) {
        return copies.undo(parse(id), revision);
    }

    @Operation(operationId = "redoWorkingCopy", summary = "Re-applies the change undone last; 409 if the revision "
        + "is stale or there is nothing to redo")
    @PostMapping(value = "/{id}/redo", produces = MediaType.APPLICATION_JSON_VALUE)
    public WorkingCopyInfo redoWorkingCopy(@PathVariable("id") String id,
        @Parameter(description = "The copy's current revision") @RequestParam("revision") long revision) {
        return copies.redo(parse(id), revision);
    }

    @Operation(operationId = "validateWorkingCopy", summary = "Validates the current text in the background; "
        + "during a run, marks it to run again on the latest revision")
    @ApiResponse(responseCode = "202", description = "The copy, its validation running", content = @Content(
        mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = WorkingCopyInfo.class)))
    @PostMapping(value = "/{id}/validations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkingCopyInfo> validateWorkingCopy(@PathVariable("id") String id,
        @Parameter(description = "Whether to check the CV rules of the validation's profile (or the default "
            + "profile of the file's version) too")
        @RequestParam(value = "semantic", defaultValue = "false") boolean semantic) {
        return ResponseEntity.accepted().body(copies.requestValidation(parse(id), semantic));
    }

    @Operation(operationId = "listWorkingCopyMetadataGroups", summary = "Lists the metadata groups of a working "
        + "copy's MTD lines")
    @GetMapping(value = "/{id}/metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<MetadataGroup> listWorkingCopyMetadataGroups(@PathVariable("id") String id) {
        return reader.metadataGroups(parse(id));
    }

    @Operation(operationId = "getWorkingCopyMetadataPage", summary = "Returns one page of a metadata group of a "
        + "working copy; lineNumber is the line in the copy")
    @GetMapping(value = "/{id}/metadata/{group}", produces = MediaType.APPLICATION_JSON_VALUE)
    public MetadataPage getWorkingCopyMetadataPage(@PathVariable("id") String id, @PathVariable("group") String group,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "50") int size,
        @Parameter(description = "A metadata key of the group; the page that holds it is returned and page is ignored")
        @RequestParam(value = "key", required = false) @Nullable String key) {
        return reader.metadata(parse(id), group, page, size, key);
    }

    @Parameter(name = "filter", in = ParameterIn.QUERY,
        description = "COLUMN:text, repeatable; the column is the first ':'-prefix that is an exact column name, the rest is the text (not split at commas)",
        array = @ArraySchema(schema = @Schema(type = "string")))
    @Operation(operationId = "getWorkingCopyTablePage", summary = "Returns one page of the SML, SMF or SME table of "
        + "a working copy; each row carries its line")
    @GetMapping(value = "/{id}/tables/{section}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TablePage getWorkingCopyTablePage(@PathVariable("id") String id, @PathVariable("section") String section,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "50") int size,
        @RequestParam(value = "sort", required = false) @Nullable String sort,
        @RequestParam(value = "descending", defaultValue = "false") boolean descending,
        @RequestParam(value = "columns", required = false) @Nullable List<String> columns,
        @Parameter(hidden = true) HttpServletRequest request) {
        UUID uuid = parse(id);
        Section s;
        try {
            s = Section.valueOf(section.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("section must be sml, smf or sme.");
        }
        // read raw: binding to a List would split a single value at commas
        String[] filter = request.getParameterValues("filter");
        List<String> rawFilters = new ArrayList<>();
        if (filter != null) {
            for (String raw : filter) {
                if (raw.indexOf(':') < 1) {
                    throw new IllegalArgumentException("filter must have the form COLUMN:text.");
                }
                rawFilters.add(raw);
            }
        }
        TableQuery query = new TableQuery(page, size, sort == null || sort.isBlank() ? null : sort, descending,
            List.of(), columns == null ? List.of() : columns, rawFilters);
        return reader.table(uuid, s, query).orElseThrow(() -> new NoSuchWorkingCopyException("No such table."));
    }

    @Operation(operationId = "getWorkingCopyLines", summary = "Returns raw lines of a working copy: count lines "
        + "(1 to 1000) from line from (1 to the line count + 1)")
    @GetMapping(value = "/{id}/lines", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Line> getWorkingCopyLines(@PathVariable("id") String id, @RequestParam("from") long from,
        @RequestParam("count") int count) {
        return reader.lines(parse(id), from, count);
    }

    @Operation(operationId = "downloadWorkingCopy", summary = "Downloads the current text: tsv always, byte for "
        + "byte; json only when the last validation is of the current revision and found no error (else 409)")
    @ApiResponse(responseCode = "200", content = {
        @Content(mediaType = TSV, schema = @Schema(type = "string", format = "binary")),
        @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "string", format = "binary"))})
    @GetMapping("/{id}/file")
    public void downloadWorkingCopy(@PathVariable("id") String id,
        @Parameter(description = "tsv or json") @RequestParam(value = "format", defaultValue = "tsv") String format,
        @Parameter(hidden = true) HttpServletResponse response) throws IOException {
        UUID uuid = parse(id);
        String f = format.toLowerCase(Locale.ROOT);
        if (!f.equals("tsv") && !f.equals("json")) {
            throw new IllegalArgumentException("format must be tsv or json.");
        }
        String fileName = copies.fileName(uuid);
        if (f.equals("tsv")) {
            Download out = new Download(response, TSV + ";charset=UTF-8", tsvName(fileName));
            copies.writeTsv(uuid, out);
            out.finish();
        } else {
            Download out = new Download(response, MediaType.APPLICATION_JSON_VALUE, stem(fileName) + ".json");
            copies.writeJson(uuid, out);
            out.finish();
        }
    }

    @Operation(operationId = "downloadWorkingCopyOriginal", summary = "Downloads the file as it was uploaded")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
        schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/{id}/original")
    public void downloadWorkingCopyOriginal(@PathVariable("id") String id,
        @Parameter(hidden = true) HttpServletResponse response) throws IOException {
        UUID uuid = parse(id);
        Download out = new Download(response, MediaType.APPLICATION_OCTET_STREAM_VALUE, copies.fileName(uuid));
        copies.writeOriginal(uuid, out);
        out.finish();
    }

    private static UUID parse(String id) {
        return parse(id, "id");
    }

    private static UUID parse(String id, String name) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be a UUID.");
        }
    }

    /** A JSON upload downloads as tab separated text under its stem; any other name is kept. */
    static String tsvName(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".json") ? stem(fileName) + ".mzTab" : fileName;
    }

    static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * The response body of a download. Its headers are set at the first byte,
     * so an error before it (409, 404, 503) is still answered as JSON by the
     * error advice.
     */
    private static final class Download extends OutputStream {

        private final HttpServletResponse response;
        private final String contentType;
        private final String fileName;
        private OutputStream out;

        Download(HttpServletResponse response, String contentType, String fileName) {
            this.response = response;
            this.contentType = contentType;
            this.fileName = fileName;
        }

        private OutputStream out() throws IOException {
            if (out == null) {
                response.setContentType(contentType);
                boolean ascii = StandardCharsets.US_ASCII.newEncoder().canEncode(fileName);
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(fileName, ascii ? null : StandardCharsets.UTF_8).build().toString());
                out = response.getOutputStream();
            }
            return out;
        }

        @Override
        public void write(int b) throws IOException {
            out().write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out().write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (out != null) {
                out.flush();
            }
        }

        /** Sets the headers of an empty body too. */
        void finish() throws IOException {
            out().flush();
        }
    }
}
